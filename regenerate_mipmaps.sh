#!/bin/bash
# Regenerate mipmap fallback PNGs with the new no-ring foreground
# This composites the new foreground over the background at each density size.

BG="app/src/main/res/drawable/ic_launcher_background.png"
FG="app/src/main/res/drawable/ic_launcher_foreground.png"
MO="app/src/main/res/drawable/ic_launcher_monochrome.png"

# Android mipmap densities: mdpi=48, hdpi=72, xhdpi=96, xxhdpi=144, xxxhdpi=192
declare -A SIZES
SIZES[mdpi]=48
SIZES[hdpi]=72
SIZES[xhdpi]=96
SIZES[xxhdpi]=144
SIZES[xxxhdpi]=192

for DENSITY in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    SIZE=${SIZES[$DENSITY]}
    DIR="app/src/main/res/mipmap-${DENSITY}"
    
    echo "Generating ${DENSITY} (${SIZE}x${SIZE})..."
    
    # ic_launcher.png: composite foreground over background, square
    ffmpeg -y -loglevel error \
        -i "$BG" -i "$FG" \
        -filter_complex "[0:v]scale=${SIZE}:${SIZE}:flags=lanczos,format=rgba[bg];[1:v]scale=${SIZE}:${SIZE}:flags=lanczos,format=rgba[fg];[bg][fg]overlay=0:0:format=auto" \
        "$DIR/ic_launcher.png"
    
    # ic_launcher_round.png: composite foreground over background, then apply circular mask
    ffmpeg -y -loglevel error \
        -i "$BG" -i "$FG" \
        -filter_complex "
            [0:v]scale=${SIZE}:${SIZE}:flags=lanczos,format=rgba[bg];
            [1:v]scale=${SIZE}:${SIZE}:flags=lanczos,format=rgba[fg];
            [bg][fg]overlay=0:0:format=auto[composed];
            color=c=black:s=${SIZE}x${SIZE}:d=1,format=gray,
            geq='p(X,Y)=if(lt(pow(X-${SIZE}/2,2)+pow(Y-${SIZE}/2,2),pow(${SIZE}/2,2)),255,0)'[mask];
            [composed][mask]alphamerge
        " \
        "$DIR/ic_launcher_round.png"
    
    echo "  -> $DIR/ic_launcher.png"
    echo "  -> $DIR/ic_launcher_round.png"
done

echo "All mipmap fallbacks regenerated."
