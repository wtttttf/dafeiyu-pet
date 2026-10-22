# -*- coding: utf-8 -*-
"""
检查一段透明 WebM 的 alpha：抽几帧，算出不透明像素的包围盒。

用法：
    python3 tools/webm_alpha_bbox.py assets/webm_custom/我的动作.webm [帧号...]

为什么要专门写这个：
  1. ffprobe 会把带 alpha 的 VP9 报成 yuv420p（alpha 是旁边那条 BlockAdditional 通道，
     不在像素格式里），所以**光看 ffprobe 分不出有没有 alpha**。
  2. ffmpeg 的原生 vp9 解码器**会忽略 alpha 平面**（解出来一片黑，看着像"没有 alpha"），
     必须显式指定 -c:v libvpx-vp9 才解得出真 alpha。
  这两个坑都踩过，别再踩一遍。
"""
import os
import subprocess
import sys
import tempfile
import zlib
import struct

# 上游 dsh-pet 的画布与角色包围盒（x0,y0,x1,y1），pet.html 的 CROP 就是按这个算的
CW, CH = 640, 360
BODY = (212, 60, 428, 330)


def read_png(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n', path
    i, w, h, ct, idat = 8, None, None, None, b''
    while i < len(d):
        ln = struct.unpack('>I', d[i:i + 4])[0]
        typ = d[i + 4:i + 8]
        data = d[i + 8:i + 8 + ln]
        i += 12 + ln
        if typ == b'IHDR':
            w, h, _bd, ct = struct.unpack('>IIBB', data[:10])
        elif typ == b'IDAT':
            idat += data
        elif typ == b'IEND':
            break
    raw = zlib.decompress(idat)
    bpp = 4 if ct == 6 else 3
    stride = w * bpp
    out, prev, pos = bytearray(), bytearray(stride), 0
    for _ in range(h):
        f = raw[pos]; pos += 1
        line = bytearray(raw[pos:pos + stride]); pos += stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out += line
        prev = line
    return w, h, bpp, bytes(out)


def alpha_bbox(path):
    w, h, bpp, px = read_png(path)
    minx, maxx, miny, maxy, nz = 10 ** 9, -1, 10 ** 9, -1, 0
    for y in range(h):
        row = y * w * bpp
        for x in range(w):
            if px[row + x * bpp + 3] > 8:
                nz += 1
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
    return (minx, maxx, miny, maxy), nz, (w, h)


def frames(path, which):
    """抽帧成 PNG。注意必须 -c:v libvpx-vp9，否则 alpha 平面被丢掉。"""
    d = tempfile.mkdtemp(prefix='alphachk')
    for n in which:
        subprocess.run(['ffmpeg', '-y', '-v', 'error', '-c:v', 'libvpx-vp9', '-i', path,
                        '-vf', 'select=eq(n\\,%d)' % n, '-vframes', '1',
                        '-pix_fmt', 'rgba', os.path.join(d, 'f%d.png' % n)], check=True)
    return d


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    which = [int(x) for x in sys.argv[2:]] or [1, 12, 24]
    d = frames(path, which)
    worst = None
    ok = True
    for n in which:
        p = os.path.join(d, 'f%d.png' % n)
        if not os.path.exists(p):
            print(f'  第 {n} 帧：文件里没有这一帧（片子太短？）')
            continue
        (x0, x1, y0, y1), nz, (w, h) = alpha_bbox(p)
        if nz == 0:
            print(f'  第 {n} 帧：整帧全透明 —— 要么是空帧，要么 alpha 丢了')
            ok = False
            continue
        over = ''
        if y0 < BODY[1] - 2 or y1 > BODY[3] + 2 or x0 < BODY[0] - 2 or x1 > BODY[2] + 2:
            over = '   ← 超出角色包围盒(会跟气泡/裁切打架)'
            ok = False
        print(f'  第 {n} 帧：画布 {w}x{h}  不透明 {nz} px  包围盒 x[{x0}..{x1}] y[{y0}..{y1}]{over}')
        if worst is None or y0 < worst[2]:
            worst = (x0, x1, y0, y1)
    print(f'\n参考：角色包围盒应为 x[{BODY[0]}..{BODY[2]}] y[{BODY[1]}..{BODY[3]}]（画布 {CW}x{CH}）')
    if worst:
        print(f'      这几帧合起来 x[{worst[0]}..{worst[1]}] y[{worst[2]}..{worst[3]}]')
    print('结论：' + ('✓ 有 alpha，且没跑出包围盒' if ok else '✗ 有问题，见上面标注'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
