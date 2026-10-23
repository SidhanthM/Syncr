#!/usr/bin/env python3
"""Independent encoder for the new SYNO command tags; existing tags are untouched."""
import json
import pathlib
import struct

ROOT = pathlib.Path(__file__).parent
NOTE = '00000000000000000000000001'
AUTHOR = '00000000000000000000000002'
OP = '00000000000000000000000003'
ITEM = '00000000000000000000000004'
BLOCK = '00000000000000000000000005'

def string(s):
    data = s.encode()
    return struct.pack('<I', len(data)) + data

def body(b):
    return string(json.dumps(b, separators=(',', ':'), ensure_ascii=False))

prefix = b'SYNO\x01\x00\x01' + string(NOTE) + string(AUTHOR) + string(OP) + struct.pack('<I', 0)
items = {
    'anchor': {'Anchor': {'target': {'Text': {'block_id': BLOCK, 'start': 7, 'end': 31, 'detached': False}}}},
    'margin': {'MarginNote': {'anchor_id': BLOCK, 'kind': 'Flag', 'text': 'Question?', 'reader': 'Editor', 'state': 'Pinned', 'sources': []}},
    'message': {'ThreadMessage': {'margin_id': BLOCK, 'order': 0, 'who': 'Companion', 'text': 'Reply', 'sources': []}},
    'drawing': {'Drawing': {'anchor_id': BLOCK, 'title': 'Flow', 'width': 500.0, 'height': 300.0, 'steps': [{'caption': 'Connect', 'primitives': [{'Arrow': {'from': {'x': 10.0, 'y': 20.0}, 'to': {'x': 100.0, 'y': 20.0}}}, {'Label': {'x': 10.0, 'y': 30.0, 'text': 'Cache', 'size': 14.0}}]}]}}
}
for name, value in items.items():
    encoded = prefix + b'\x08' + struct.pack('<I', 1) + string(ITEM) + b'\x00\x00' + body(value)
    (ROOT / (name + '.hex')).write_text(encoded.hex() + '\n')
settings = {'tell': 'Be brutal', 'mode': 'WhenAsked', 'web': False, 'active_reader': 'editor', 'colour': 'Blue'}
(ROOT / 'settings.hex').write_text((prefix + b'\x09\x00' + body(settings)).hex() + '\n')
