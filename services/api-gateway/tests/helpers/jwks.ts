import { createLocalJWKSet, type JSONWebKeySet, type JWK } from 'jose';

export function createLocalJWKS(keys: JWK[]) {
  const jwks: JSONWebKeySet = { keys };
  return createLocalJWKSet(jwks);
}
