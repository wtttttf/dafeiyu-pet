#!/bin/bash
# 把任意视频 / PNG 序列做成大肥鱼能用的动作片段（透明 VP9 WebM）。
#
#   bash tools/make_action.sh 我的动作.mp4 --key 00FF00      # 绿幕素材
#   bash tools/make_action.sh 'frames/%04d.png' --name 自定-挥手 --no-fit
#
# 素材从哪来（三条路，按省事程度排）：
#   A. 拿现成的 106 段改（截一段 / 变速 / 拼）—— 零成本，配 --no-fit
#   B. AI 生成一段 3~5 秒短片（图生视频最稳），再用 --key 抠背景
#   C. 自己逐帧画 PNG 序列 —— 最费劲但最可控
# 抠背景默认 chromakey（实测边缘比 RGB 空间的 colorkey 干净，不会在头发边上留一圈绿）；
# 想用 RGB 的 colorkey 加 --rgb。抠不干净就调 --sim（默认 0.25）。
#
# 做完还要跑两步才会生效：
#   python3 tools/mk_pet_html.py     # 把新片段登记进动画池
#   bash build.sh                    # 重新打包 APK
#
# 产出的片段必须满足三条硬约束（下游的裁切 / 命中判定都按它算）：
#   1. 画布 640x360，24fps
#   2. 带 alpha 通道（VP9 的 yuva420p）
#   3. 角色本人落在 x[212..428] y[60..330] 这个"身体盒"里
# 第 3 条默认自动搞定：先按 alpha 找出角色在画面里的实际范围（跨多帧取并集），
# 再等比缩放居中放进身体盒。所以绿幕素材里角色只占中间一小块也没关系。
set -euo pipefail

APP=/root/pet-app
OUT_DIR=$APP/assets/webm_custom
CW=640; CH=360
# 身体盒（和 mk_pet_html.py 的 BODY 一致）
BX=212; BY=60; BW=216; BH=270

IN=""; NAME=""; KEY=""; RGB=0; FPS=24; CRF=32; SS=""; TO=""; CROP=""; NOFIT=0; YES=0
SIM=0.25; BLEND=0.10

die() { echo "✗ $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --name)   NAME=$2; shift 2 ;;
    --key)    KEY=${2#0x}; KEY=${KEY#\#}; shift 2 ;;
    --chroma) RGB=0; shift ;;   # 兼容老写法：chromakey 现在是默认
    --rgb)    RGB=1; shift ;;
    --fps)    FPS=$2; shift 2 ;;
    --crf)    CRF=$2; shift 2 ;;
    --ss)     SS=$2; shift 2 ;;
    --to)     TO=$2; shift 2 ;;
    --crop)   CROP=$2; shift 2 ;;
    --sim)    SIM=$2; shift 2 ;;
    --blend)  BLEND=$2; shift 2 ;;
    --no-fit) NOFIT=1; shift ;;
    --yes)    YES=1; shift ;;
    -h|--help) sed -n "2,23p" "$0" | sed 's/^# \?//'; exit 0 ;;
    -*)       die "不认识的选项 $1（-h 看用法）" ;;
    *)        [ -z "$IN" ] && IN=$1 || die "只能给一个输入"; shift ;;
  esac
done

[ -n "$IN" ] || die "没给输入。用法：bash tools/make_action.sh <视频或PNG序列> [选项]"
case "$IN" in *%*|*'*') SEQ=1 ;; *) SEQ=0 ;; esac
[ "$SEQ" = 1 ] || [ -f "$IN" ] || die "找不到输入文件 $IN"

# ---------------------------------------------------------------- 取名
if [ -z "$NAME" ]; then
  if [ "$SEQ" = 1 ]; then NAME=$(basename "$(dirname "$IN")"); else NAME=$(basename "$IN"); NAME=${NAME%.*}; fi
fi
OUT="$OUT_DIR/$NAME.webm"
mkdir -p "$OUT_DIR"
if [ -e "$OUT" ] && [ "$YES" != 1 ]; then
  die "$OUT 已存在。加 --yes 覆盖，或换个 --name"
fi

# ---------------------------------------------------------------- 探源
# 【必须记住的坑】ffmpeg 自带的 vp9 解码器会忽略 alpha 平面（解出来是不透明的一整块），
# 想把 webm 里的 alpha 解码出来，非要显式 -c:v libvpx-vp9 不可。
# 少了这一句，拿现成的透明片段当输入时会静默丢掉透明 —— 而且 ffprobe 一点都看不出来。
DEC=(); FMT=()
[ "$SEQ" = 1 ] && FMT=(-f image2)
read -r SW SH SPIX SRATE SFRAMES SC < <(python3 -c '
import json, subprocess, sys
a = sys.argv[1:]
info, path = a[:-1], a[-1]
r = subprocess.run(["ffprobe", "-v", "error", *info, "-select_streams", "v:0",
                    "-show_streams", "-of", "json", path], capture_output=True, text=True)
try:
    s = json.loads(r.stdout)["streams"][0]
except Exception:
    print("? ? ? ? ? ?"); raise SystemExit
print(s.get("width", "?"), s.get("height", "?"), s.get("pix_fmt", "?"),
      s.get("r_frame_rate", "?"), s.get("nb_frames", "?"), s.get("codec_name", "?"))
' "${FMT[@]}" "$IN")
[ "$SC" = "vp9" ] && DEC=(-c:v libvpx-vp9)
[ "$SW" = "?" ] && die "读不出这个视频（格式不支持？）"
if [ "$SEQ" = 1 ]; then echo "来源：PNG 序列  ${SW}x${SH}  $SPIX"
else echo "来源：$IN  ${SW}x${SH}  $SPIX  $SRATE  帧数 $SFRAMES"; fi

# 真的有没有 alpha，ffprobe 是看不出来的（VP9 的 alpha 在旁边的 BlockAdditional 通道里，
# 像素格式照样报 yuv420p）。所以只能解一帧出来数。
ALPHA=no
if ffmpeg -v error "${DEC[@]}" "${FMT[@]}" -i "$IN" -frames:v 1 -pix_fmt rgba -f rawvideo - 2>/dev/null | \
   python3 -c "
import sys
d = sys.stdin.buffer.read()
px = [d[i] for i in range(3, len(d), 4 * 37)]   # 抽样就够
sys.exit(0 if px and min(px) < 250 else 1)
"; then ALPHA=yes; fi
echo "       解码器 ${SC:-auto}${DEC:+ (强制 libvpx-vp9)}   自带 alpha: $ALPHA"
if [ "$ALPHA" = no ] && [ -z "$KEY" ]; then
  echo "⚠ 来源没有 alpha、也没给 --key 抠背景 —— 输出会是不透明的一整块（角色周围不会是透明的）"
fi

# ---------------------------------------------------------------- 输入参数
IN_ARGS=("${DEC[@]}" "${FMT[@]}")
[ "$SEQ" = 1 ] && IN_ARGS+=(-framerate "$FPS")
[ -n "$SS" ] && IN_ARGS+=(-ss "$SS")
OUT_ARGS=(); [ -n "$TO" ] && OUT_ARGS=(-to "$TO")

# 预处理：先裁、再抠色、再统一帧率。自动适配也复用这一段，保证"看到的"和"编码的"是同一条链
PREP=""
[ -n "$CROP" ] && PREP="$PREP crop=$CROP,"
if [ -n "$KEY" ]; then
  if [ "$RGB" = 1 ]; then PREP="$PREP format=rgba,colorkey=0x$KEY:$SIM:$BLEND,"
  else PREP="$PREP chromakey=0x$KEY:$SIM:$BLEND,"; fi
fi
PREP="$PREP fps=$FPS"

# 预处理之后画面的尺寸（crop 会改变它，抽帧时要用）
PW=$SW; PH=$SH
if [ -n "$CROP" ]; then
  IFS=: read -r _cw _ch _ _ <<<"$CROP"
  PW=$_cw; PH=$_ch
fi

# ---------------------------------------------------------------- 算缩放/摆放
if [ "$NOFIT" = 1 ]; then
  echo "布局：整帧拉伸成 ${CW}x${CH}（--no-fit）"
  F="$PREP,scale=$CW:$CH,format=yuva420p"
else
  # 沿时间抽一批帧，取 alpha 的并集包围盒 —— 上游的 body_box 也是这么定的
  # （单看一帧会算小，角色一抬手就被裁掉）。
  BOX=$(ffmpeg -v error "${IN_ARGS[@]}" -i "$IN" "${OUT_ARGS[@]}" \
          -vf "$PREP,scale=$PW:$PH,fps=3" -frames:v 30 -pix_fmt rgba -f rawvideo - 2>/dev/null | \
        python3 -c '
import sys
W, H = int(sys.argv[1]), int(sys.argv[2])
d = sys.stdin.buffer.read()
fa = W * H * 4
if len(d) < fa: print("NONE"); raise SystemExit
minx, maxx, miny, maxy = 10**9, -1, 10**9, -1
for o in range(0, len(d) - fa + 1, fa):
    fr = d[o:o + fa]
    for y in range(0, H, 3):
        row = y * W * 4
        for x in range(0, W, 3):
            if fr[row + x * 4 + 3] > 8:
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
print("NONE" if maxx < 0 else "%d %d %d %d" % (minx, maxx, miny, maxy))
' "$PW" "$PH")

  if [ "$BOX" = "NONE" ]; then
    die "整段都是透明的，找不到角色。检查一下 --key 是不是把角色也抠掉了（调小 --sim 试试）"
  fi
  read -r X0 X1 Y0 Y1 <<<"$BOX"
  # 四周留一点余量，免得把半透明的边缘/特效削掉
  PAD=$(( (X1 - X0 + Y1 - Y0) / 40 + 2 ))
  CX=$(( X0 - PAD )); CY=$(( Y0 - PAD ))
  CWD=$(( X1 - X0 + 1 + 2 * PAD )); CHT=$(( Y1 - Y0 + 1 + 2 * PAD ))
  [ "$CX" -lt 0 ] && { CWD=$(( CWD + CX )); CX=0; }
  [ "$CY" -lt 0 ] && { CHT=$(( CHT + CY )); CY=0; }
  [ $(( CX + CWD )) -gt "$PW" ] && CWD=$(( PW - CX ))
  [ $(( CY + CHT )) -gt "$PH" ] && CHT=$(( PH - CY ))
  echo "主体：x[$X0..$X1] y[$Y0..$Y1]（多帧并集，画布 ${PW}x${PH}）→ 裁 $CWD x $CHT @ $CX,$CY"

  read -r TW TH TX TY < <(python3 -c '
import sys
bw, bh, bx, by, bwmax, bhmax = (int(x) for x in sys.argv[1:7])
a = bw / bh
if a > bwmax / bhmax:
    w, h = bwmax, round(bwmax / a)
else:
    h, w = bhmax, round(bhmax * a)
w -= w % 2; h -= h % 2                      # yuva420p 要求宽高都是偶数
print(w, h, bx + (bwmax - w) // 2, by + (bhmax - h) // 2)
' "$CWD" "$CHT" "$BX" "$BY" "$BW" "$BH")
  echo "布局：裁切块 ${CWD}x${CHT} → 缩放 ${TW}x${TH} → 摆在 ${TX},${TY}"
  F="$PREP,crop=$CWD:$CHT:$CX:$CY,scale=$TW:$TH:flags=lanczos,pad=$CW:$CH:$TX:$TY:color=black@0,format=yuva420p"
fi

# ---------------------------------------------------------------- 编码
echo "编码中…（VP9 纯 CPU，一段 3 秒的片段在手机上要等一会儿）"
ffmpeg -y -v warning -stats \
  "${IN_ARGS[@]}" -i "$IN" "${OUT_ARGS[@]}" \
  -vf "$F" -an \
  -c:v libvpx-vp9 -pix_fmt yuva420p -auto-alt-ref 0 -b:v 0 -crf "$CRF" \
  -deadline good -cpu-used 3 -row-mt 1 \
  "$OUT"

# ---------------------------------------------------------------- 验收
SZ=$(stat -c%s "$OUT")
echo ""
echo "产出 $OUT  $((SZ/1024)) KB"
python3 "$APP/tools/webm_alpha_bbox.py" "$OUT" || {
  echo ""
  echo "⚠ 上面报了问题。对一下这几个原因："
  echo "   · 包围盒跑出身体盒 → 角色在片段里动得比抽到的帧还大，调小 --sim/bigger crop，或干脆 --no-fit"
  echo "   · 整帧全透明       → 抠色太狠，调小 --sim（默认 0.25）"
  echo "   · 整帧不透明       → 背景没抠掉，调大 --sim 或换个更纯的背景色"
  exit 1
}

echo ""
echo "下一步："
echo "  python3 tools/mk_pet_html.py     # 登记进动画池（会打印它被分到哪个池子）"
echo "  bash build.sh                    # 打包（APK 大约 +$((SZ/1048576)) MB）"
echo ""
echo "片段名「$NAME」按前缀决定进哪个池："
echo "  点击回应-*  → 点她时的回应        余额-*  → 余额动画"
echo "  待机呼吸休闲 → 待机循环(替换原待机)  被鼠标拖拽悬空反馈 → 拖动中"
echo "  原地左转奔跑 / 原地漂浮踏步 / 螃蟹走路 → 自动走动（还得会标 stride 曲线，见 mk_pet_html.py）"
echo "  其余任何名字 → 随机动作池（每 7~20 秒随机挑一个演）"
echo "  不想改文件名的，改 tools/actions.json 指定池子"
