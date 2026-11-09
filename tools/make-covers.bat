@echo off
rem Generate cover thumbnails for playlist items that have no cover yet.
rem Frames are grabbed with ffmpeg from the video on the NAS, then uploaded to
rem <playlistShare>/curated/covers/<hash>.jpg and merged into the live tv.json
rem (a backup is written first). Safe to run again: existing covers are kept.
chcp 65001 >nul
cd /d "%~dp0.."
echo.
echo   ============================================================
echo      Generate covers from video frames
echo   ============================================================
echo      Only items without a cover are processed.
echo      Needs ffmpeg in PATH (winget install Gyan.FFmpeg).
echo   ============================================================
echo.
python tools\make_covers.py --from-nas --apply %*
if errorlevel 1 (
  echo.
  echo   [FAILED] Check: python -m pip install -r tools\requirements.txt
  pause
)
