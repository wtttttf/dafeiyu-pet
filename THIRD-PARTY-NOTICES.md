# 第三方来源与许可

本仓库的 **源代码** 以 MIT 协议发布（见 `LICENSE`）。以下为第三方内容与素材的说明。

## 1. elyoncatnecoe/whale-pet（MIT）

`assets/pet.html` 的舞台布局、双缓冲播放与互动特效参考/移植自：

- https://github.com/elyoncatnecoe/whale-pet
- 许可：MIT

```
MIT License

Copyright (c) 2026 elyoncatnecoe

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 2. 角色形象、动画与音效

`assets/webm/`、`assets/sounds/`、`res/drawable/` 中的角色形象、动画和音效 **不在 MIT 授权范围内**。

- 虎鲸 / 鲸鱼形象是 **DeepSeek** 的品牌标识与商标，权利归 DeepSeek 所有。
- 本项目是个人非官方的爱好者作品，与 DeepSeek 官方无关联、未获背书或赞助。
- 请勿将角色形象用于商业用途或商标性使用，也不要暗示与 DeepSeek 存在官方关系。
- 如需转载素材，请先确认原作者与权利方许可。

## 3. 依赖

构建期使用 Android SDK（`aapt2` / `d8` / `apksigner` / `zipalign`），运行期使用 Android WebView；二者遵循各自许可。仓库不包含这些二进制。
