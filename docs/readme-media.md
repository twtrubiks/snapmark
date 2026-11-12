# README 的截圖與 GIF

`docs/images/`：都從 CI 的 `media` branch 來（Android 9 模擬器）。畫面改了要換時照這樣重做：

- 截圖：`/tmp/media/screenshots/` 的 `00-camera`（→ `home`）、`10-all-tools`（→ `tools`）、`08-tool-picker`（→ `tool-menu`）、
  `04-color-picker`（→ `colors`）、`13-mosaic`（→ `mosaic`）、`13c-crop`（→ `crop`）、`14-saved`（→ `saved`）、`01b-dark`（→ `dark`），
  `convert <來源> -resize 300x -strip -define png:compression-level=9 docs/images/<名字>.png`
- `demo.gif`：`demo.mp4` 剪幾段（首頁與深色模式、箭頭與顏色、選取、形狀／筆／文字／印章、照片打馬賽克、裁切、存到相簿 → 作品分頁 → 繼續改；
  Android 9 問權限的系統對話框剪掉），每段 `trim` 之後 `setpts=(PTS-STARTPTS)/4` 加速 4 倍、`concat`，
  再 `fps=8,scale=300:-1`、`palettegen=max_colors=128:stats_mode=diff`／`paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle`。
  45 秒、約 2 MB。錄影每次長度不一樣，先用每 5 秒一張的影格對好時間再剪
