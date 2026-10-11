from __future__ import annotations

from virbius_integrate.masker import mask


def test_masks_cn_phone():
    assert "13812345678" not in mask("call 13812345678 now")


def test_masks_email():
    masked = mask("contact alice@example.com please")
    assert "alice@example.com" not in masked
    assert "a***@example.com" in masked


def test_masks_id_card():
    masked = mask("id=11010119900307891X")
    assert "11010119900307891X" not in masked


def test_masks_bank_card():
    masked = mask("card 6222020200112233345")
    assert "6222020200112233345" not in masked


def test_keeps_normal_text():
    text = "query users where status=active"
    assert mask(text) == text


def test_none_passthrough():
    assert mask(None) is None
    assert mask("") == ""
