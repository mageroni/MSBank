"""Jinja2 rendering helpers."""
from __future__ import annotations

from pathlib import Path
from typing import Any

from jinja2 import Environment, FileSystemLoader, StrictUndefined, select_autoescape

TEMPLATES_DIR = Path(__file__).resolve().parents[2] / "templates"


def _make_env(templates_dir: Path = TEMPLATES_DIR) -> Environment:
    return Environment(
        loader=FileSystemLoader(str(templates_dir)),
        autoescape=select_autoescape(enabled_extensions=("html", "xml")),
        undefined=StrictUndefined,
        trim_blocks=True,
        lstrip_blocks=True,
        enable_async=False,
    )


_env: Environment = _make_env()


def get_env() -> Environment:
    """Return the shared Jinja2 environment."""
    return _env


def render_template(template_key: str, context: dict[str, Any]) -> str:
    """Render a Jinja2 template by its relative path (e.g. ``email/welcome.html``)."""
    template = _env.get_template(template_key)
    return template.render(**context)
