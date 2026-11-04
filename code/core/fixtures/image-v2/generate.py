"""Independent image SYNO tag-10 vectors. PNG uses standard-library zlib only."""
import pathlib
import struct
import zlib
root = pathlib.Path(__file__).parent
ident = lambda n: f'{n:026}'
def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(b'\0\xff\xff\xff')) + chunk(b'IEND', b'')
(root / 'pixel.png').write_bytes(png)
def string(s):
    bs = s.encode()
    return struct.pack('<I', len(bs)) + bs
def blob(bs):
    return struct.pack('<I', len(bs)) + bs
prefix = b'SYNO\x01\x00\x01' + b''.join(string(ident(n)) for n in (1,2,3)) + struct.pack('<I', 0)
prefix += b'\x0a' + struct.pack('<I', 1)
canvas = b'\x00' + string(ident(5)) + struct.pack('<dddd',10.,20.,100.,100.)
text = b'\x01' + string(ident(5)) + struct.pack('<dd',200.,100.)
vectors = {
    'add': b'\x00' + string(ident(4)) + blob(png) + b'\x00' + struct.pack('<II',1,1) + canvas,
    'delete': b'\x01' + string(ident(4)) + b'\x00',
    'restore': b'\x02' + string(ident(4)) + b'\x00',
    'move': b'\x03' + string(ident(4)) + b'\x00' + text,
    'seen': b'\x04' + string(ident(4)) + b'\x00',
}
for name, body in vectors.items():
    (root / (name + '.hex')).write_text((prefix + body).hex() + '\n')
print('Image canonical vectors:', ', '.join(vectors))
