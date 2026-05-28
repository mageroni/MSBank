"""Tests for Jinja2 rendering."""
from __future__ import annotations

from app.templating.render import render_template


def test_welcome_template_contains_first_name() -> None:
    html = render_template("email/welcome.html", {"first_name": "Ada", "subject": "Welcome"})
    assert "Welcome, Ada!" in html
    assert "Microservice Bank" in html  # brand header from base layout


def test_transfer_completed_template_has_amount_and_reference() -> None:
    html = render_template(
        "email/transfer_completed.html",
        {
            "subject": "ok",
            "amount": "100.00 USD",
            "source_account": "src",
            "destination_account": "dst",
            "transfer_id": "abc-123",
        },
    )
    assert "100.00 USD" in html
    assert "abc-123" in html


def test_sms_transfer_completed_template() -> None:
    txt = render_template(
        "sms/transfer_completed.txt",
        {"amount": "50.00 USD", "transfer_id": "t-1"},
    )
    assert "t-1" in txt
    assert "50.00 USD" in txt
