package com.gathin.tvhome;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * An app's icon as a round tile (config round_tiles), as Google TV and tvOS show apps: an adaptive icon's two
 * layers fill the whole circle, the way the system mask would show them; an older icon sits on a white disc.
 * The tile's outline (AppAdapter, cornerRadius) does the clipping, so this only paints a square.
 */
final class RoundIcon {
    private RoundIcon() {
    }

    static Drawable of(Resources res, Drawable icon, int size) {
        if (icon == null || size <= 0) return null;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable d = icon.getConstantState() != null ? icon.getConstantState().newDrawable(res).mutate() : icon;
        if (d instanceof AdaptiveIconDrawable) {
            // Layers are 108dp with the visible part in the middle 72dp: draw them half as large again as the tile,
            // centred, so the circle shows what a launcher mask would.
            int pad = size / 4;
            Rect r = new Rect(-pad, -pad, size + pad, size + pad);
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            Drawable bg = a.getBackground(), fg = a.getForeground();
            if (bg != null) {
                bg.setBounds(r);
                bg.draw(c);
            } else {
                c.drawColor(Color.WHITE);
            }
            if (fg != null) {
                fg.setBounds(r);
                fg.draw(c);
            }
        } else if (fillsSquare(d, size)) {
            // A square icon with its own background (Apple TV's dark tile): the circle inside it shows that background
            // edge to edge, and even a rounded square's corners stay outside the circle.
            d.setBounds(0, 0, size, size);
            d.draw(c);
        } else {
            c.drawColor(Color.WHITE);
            // The circle loses its corners: keep the icon inside the inscribed square, with a little air.
            int inset = Math.round(size * 0.2f);
            d.setBounds(inset, inset, size - inset, size - inset);
            d.draw(c);
        }
        BitmapDrawable out = new BitmapDrawable(res, b);
        out.setFilterBitmap(true);
        return out;
    }

    /** True when the icon is opaque at the middle of all four edges, i.e. it is a full square or rounded square. */
    private static boolean fillsSquare(Drawable d, int size) {
        int s = Math.max(32, Math.min(size, 96));
        Bitmap probe = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, s, s);
        d.draw(new Canvas(probe));
        int in = Math.max(1, s / 25), mid = s / 2;
        int[][] points = {{mid, in}, {mid, s - 1 - in}, {in, mid}, {s - 1 - in, mid}};
        boolean opaque = true;
        for (int[] p : points) opaque &= Color.alpha(probe.getPixel(p[0], p[1])) > 200;
        probe.recycle();
        return opaque;
    }
}
