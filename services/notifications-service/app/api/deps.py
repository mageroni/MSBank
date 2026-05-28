"""Request-scoped FastAPI dependencies (auth + DI)."""
from __future__ import annotations

import time
import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any, cast

import httpx
from cachetools import TTLCache
from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.db.session import Database
from app.domain.enums import Role

bearer_scheme = HTTPBearer(auto_error=False)


@dataclass(slots=True, frozen=True)
class CurrentUser:
    """Authenticated principal extracted from a bearer JWT."""

    user_id: uuid.UUID
    email: str | None
    roles: tuple[Role, ...]

    def has_role(self, role: Role) -> bool:
        return role in self.roles


# ---------------------------------------------------------------------------
# JWKS cache (TTL'd) keyed by issuer URL
# ---------------------------------------------------------------------------

_JWKS_CACHE: TTLCache[str, dict[str, Any]] = TTLCache(maxsize=4, ttl=300)


async def _fetch_jwks(jwks_url: str) -> dict[str, Any]:
    cached = _JWKS_CACHE.get(jwks_url)
    if cached is not None:
        return cached
    async with httpx.AsyncClient(timeout=5.0) as client:
        response = await client.get(jwks_url)
        response.raise_for_status()
        jwks = cast(dict[str, Any], response.json())
    _JWKS_CACHE[jwks_url] = jwks
    return jwks


def _key_from_jwks(jwks: dict[str, Any], kid: str | None) -> dict[str, Any]:
    for key in jwks.get("keys", []):
        if kid is None or key.get("kid") == kid:
            return cast(dict[str, Any], key)
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Signing key not found",
    )


async def _verify_jwt(token: str, settings: Settings) -> dict[str, Any]:
    """Verify an RS256 JWT against the issuer's JWKS (or static PEM in dev)."""
    try:
        unverified_header = jwt.get_unverified_header(token)
    except JWTError as exc:
        raise HTTPException(status_code=401, detail=f"Invalid token header: {exc}") from exc

    key: Any
    if settings.jwt_public_key_pem:
        key = settings.jwt_public_key_pem
    else:
        jwks_url = settings.jwt_jwks_uri or (settings.jwt_issuer.rstrip("/") + "/.well-known/jwks.json")
        jwks = await _fetch_jwks(jwks_url)
        key = _key_from_jwks(jwks, unverified_header.get("kid"))

    try:
        claims = jwt.decode(
            token,
            key,
            algorithms=list(settings.jwt_algorithms),
            audience=settings.jwt_audience,
            issuer=settings.jwt_issuer,
            options={"verify_at_hash": False},
        )
    except JWTError as exc:
        raise HTTPException(status_code=401, detail=f"Invalid token: {exc}") from exc

    exp = claims.get("exp")
    if exp is not None and int(exp) < int(time.time()):
        raise HTTPException(status_code=401, detail="Token expired")
    return cast(dict[str, Any], claims)


async def get_current_user(
    creds: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
    settings: Settings = Depends(get_settings),
) -> CurrentUser:
    """Resolve the authenticated principal from the Authorization header."""
    if creds is None or not creds.credentials:
        raise HTTPException(status_code=401, detail="Missing bearer token")
    claims = await _verify_jwt(creds.credentials, settings)

    sub = claims.get("sub") or claims.get("userId")
    if sub is None:
        raise HTTPException(status_code=401, detail="Token missing subject")
    try:
        user_id = uuid.UUID(str(sub))
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="Subject is not a UUID") from exc

    raw_roles = claims.get("roles") or []
    if isinstance(raw_roles, str):
        raw_roles = [raw_roles]
    roles: list[Role] = []
    for role in raw_roles:
        try:
            roles.append(Role(role))
        except ValueError:
            continue

    return CurrentUser(user_id=user_id, email=claims.get("email"), roles=tuple(roles))


def require_admin(user: CurrentUser = Depends(get_current_user)) -> CurrentUser:
    """Ensure the current user has the ADMIN role."""
    if not user.has_role(Role.ADMIN):
        raise HTTPException(status_code=403, detail="Admin role required")
    return user


# ---------------------------------------------------------------------------
# Request-scoped DB session
# ---------------------------------------------------------------------------


async def get_db(request: Request) -> AsyncIterator[AsyncSession]:
    """Yield a request-scoped async DB session."""
    database = cast(Database, request.app.state.database)
    async with database.session() as session:
        yield session
