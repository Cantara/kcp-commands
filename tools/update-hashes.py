#!/usr/bin/env python3
"""Refresh content_hash values in knowledge.yaml for all local unit files.

Run this before committing whenever you edit a file that is referenced by a
unit in knowledge.yaml with a content_hash field.

Usage:
    python3 tools/update-hashes.py
"""
import hashlib
import sys
import yaml

MANIFEST = "knowledge.yaml"

with open(MANIFEST) as f:
    text = f.read()

manifest = yaml.safe_load(text)
updated = 0

for unit in manifest.get("units", []):
    path = unit.get("path", "")
    ch = unit.get("content_hash", {})
    if not ch or not path or path.startswith("http"):
        continue
    old = ch.get("value", "")
    if not old:
        continue
    try:
        actual = hashlib.sha256(open(path, "rb").read()).hexdigest()
    except FileNotFoundError:
        print(f"  skip  {path}  (not found)")
        continue
    if old == actual:
        print(f"  ok    {path}  ({actual[:16]}...)")
    else:
        text = text.replace(old, actual)
        print(f"  fixed {path}")
        print(f"        {old[:16]}... -> {actual[:16]}...")
        updated += 1

if updated:
    with open(MANIFEST, "w") as f:
        f.write(text)
    print(f"\n{updated} hash(es) updated in {MANIFEST}")
else:
    print(f"\nAll hashes already match — nothing to do")
