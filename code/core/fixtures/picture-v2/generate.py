"""Independent SYNO encoder for the new Picture body; never changes older vectors."""
import json
import pathlib
import struct

root = pathlib.Path(__file__).parent
ident = lambda n: f"{n:026}"
svg = '<svg viewBox="0 0 320 200"><g data-caption="Start" data-step="1"><line stroke="var(--ink)" x1="10" x2="100" y1="20" y2="20"/><text x="10" y="40">Label</text></g></svg>'
body = {"Picture": {"anchor_id": ident(5), "title": "Flow", "svg": svg}}
command = {"operation_id": ident(3), "dependencies": [], "body": {"CompanionMarks": {"mutations": [{"id": ident(4), "base": "Absent", "tombstone": False, "body": body}]}}}
def string(s):
    b = s.encode()
    return struct.pack('<I', len(b)) + b
encoded = b'SYNO\x01\x00\x01' + b''.join(string(ident(n)) for n in (1, 2, 3))
encoded += struct.pack('<I', 0) + b'\x08' + struct.pack('<I', 1)
encoded += string(ident(4)) + b'\x00\x00' + string(json.dumps(body, ensure_ascii=False, separators=(',', ':')))
(root / 'picture.command.json').write_text(json.dumps(command, ensure_ascii=False, indent=2) + '\n')
(root / 'picture.hex').write_text(encoded.hex() + '\n')
print('Picture canonical vector:', len(encoded), 'bytes')
