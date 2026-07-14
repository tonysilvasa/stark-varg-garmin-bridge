//
// StarkBridgeView.mc
//
// The data field view. It does no BLE work itself: it reads the latest
// telemetry (and staleness) off the AppBase and renders two values with a
// label, e.g.:
//
//        STARK VARG
//     84%      SPORT
//
// compute() returns null because this field does not contribute a numeric
// value to the activity/FIT record; it is a pure display field driven by BLE.
//

import Toybox.Lang;
using Toybox.WatchUi;
using Toybox.Graphics;
using Toybox.Activity;

class StarkBridgeView extends WatchUi.DataField {

    // Cached geometry, refreshed in onLayout.
    private var _w as Number = 0;
    private var _h as Number = 0;

    function initialize() {
        DataField.initialize();
    }

    function onLayout(dc as Graphics.Dc) as Void {
        _w = dc.getWidth();
        _h = dc.getHeight();
    }

    // Pure display field: no FIT value. Returning null keeps the framework
    // happy without recording anything. We deliberately do not stash state in
    // compute() (it may run before onUpdate); telemetry flows via the app.
    function compute(info as Activity.Info) as Numeric or Null {
        return null;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        // Keep geometry current (some products call onUpdate before onLayout).
        _w = dc.getWidth();
        _h = dc.getHeight();

        // Honor the host's chosen background (data field may be inverted).
        var bg = getBackgroundColor();
        var fg = (bg == Graphics.COLOR_BLACK) ? Graphics.COLOR_WHITE : Graphics.COLOR_BLACK;

        dc.setColor(fg, bg);
        dc.clear();

        var app = $.getStarkApp();
        var telemetry = app.getTelemetry();

        // Resolve the two display strings. Show the LAST known values even when
        // the feed goes quiet/inactive; only fall back to a placeholder if we
        // have never received any data at all.
        var batteryStr;
        var modeStr;
        if (telemetry == null) {
            batteryStr = "--";
            modeStr = app.isConnected() ? "LINK" : "SCAN";
        } else {
            var battery = telemetry[:battery] as Number?;   // null => unknown
            var mode = telemetry[:mode] as Number?;
            batteryStr = (battery == null) ? "??%" : battery.toString() + "%";
            modeStr = Contract.modeLabel(mode);
        }

        // Battery color cue, based on the last reading (live or held).
        var batteryColor = fg;
        if (telemetry != null) {
            var b = telemetry[:battery] as Number?;
            if (b != null) {
                if (b <= 10) {
                    batteryColor = Graphics.COLOR_RED;
                } else if (b <= 25) {
                    batteryColor = Graphics.COLOR_ORANGE;
                } else {
                    batteryColor = Graphics.COLOR_GREEN;
                }
            }
            // Fault flag overrides: warn in red regardless of charge.
            var fault = telemetry[:faultActive] as Boolean?;
            if (fault != null && fault) {
                batteryColor = Graphics.COLOR_RED;
            }
        }

        // Mode colour cue + battery gauge inputs.
        var modeColor = fg;
        var batteryPct = null;
        var charging = false;
        if (telemetry != null) {
            modeColor = modeColorFor(telemetry[:mode] as Number?);
            batteryPct = telemetry[:battery] as Number?;
            var c = telemetry[:charging] as Boolean?;
            charging = (c != null && c);
        }

        // Battery gauge bar (top) + ride-mode hero (big, colour-coded, below).
        drawHero(dc, batteryStr, batteryColor, batteryPct, charging,
                 modeStr, modeColor, telemetry != null);

    }

    // ---- Layout helpers -----------------------------------------------------

    // Battery gauge bar across the top, then the ride mode as the dominant
    // element below: a big, colour-coded value with a lightning bolt.
    private function drawHero(dc as Graphics.Dc,
                              batteryStr as String, batteryColor as Graphics.ColorType,
                              batteryPct as Number?, charging as Boolean,
                              modeStr as String, modeColor as Graphics.ColorType,
                              hasData as Boolean) as Void {
        // --- Battery gauge bar (fuel-gauge style) across the top ---
        var barH = (_h * 22) / 100;
        if (barH < 16) { barH = 16; }
        var barX = 3;
        var barY = 2;
        var barW = _w - 6;

        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(barX, barY, barW, barH);
        if (batteryPct != null) {
            var pct = batteryPct;
            if (pct < 0) { pct = 0; }
            if (pct > 100) { pct = 100; }
            dc.setColor(batteryColor, Graphics.COLOR_TRANSPARENT);
            dc.fillRectangle(barX, barY, (barW * pct) / 100, barH);
        }
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawRectangle(barX, barY, barW, barH);
        if (charging) {
            dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
            dc.drawText(barX + 6, barY + barH / 2, Graphics.FONT_TINY, "CHG",
                        Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(barX + barW - 6, barY + barH / 2, Graphics.FONT_SMALL, batteryStr,
                    Graphics.TEXT_JUSTIFY_RIGHT | Graphics.TEXT_JUSTIFY_VCENTER);

        // --- Ride-mode hero below the bar ---
        var heroTop = barY + barH + 3;
        var heroAvail = _h - heroTop - 1;

        // Mode value: biggest font that fits the space below the bar. Numeric
        // modes use the large numeric fonts; named modes use text fonts.
        var maxH = (heroAvail * 94) / 100;
        var numLadder = [
            Graphics.FONT_NUMBER_THAI_HOT, Graphics.FONT_NUMBER_HOT,
            Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD,
            Graphics.FONT_LARGE, Graphics.FONT_MEDIUM
        ];
        var txtLadder = [
            Graphics.FONT_LARGE, Graphics.FONT_MEDIUM, Graphics.FONT_SMALL,
            Graphics.FONT_TINY, Graphics.FONT_XTINY
        ];
        var ladder = isAllDigits(modeStr) ? numLadder : txtLadder;

        // Reserve ~22% of the width for the bolt when we have live data.
        var valMaxW = hasData ? (((_w - 8) * 78) / 100) : (_w - 8);
        var valFont = bestFont(dc, modeStr, valMaxW, maxH, ladder);
        var valH = dc.getFontHeight(valFont);
        var valW = dc.getTextWidthInPixels(modeStr, valFont);

        var boltW = 0;
        var boltH = 0;
        var gap = 0;
        if (hasData) {
            boltH = (valH * 70) / 100;
            boltW = (boltH * 60) / 100;
            gap = 8;
        }

        var totalW = valW + boltW + gap;
        var startX = (_w - totalW) / 2;
        if (startX < 2) { startX = 2; }
        var cy = heroTop + heroAvail / 2;

        if (hasData) {
            drawBolt(dc, startX, cy - boltH / 2, boltW, boltH, modeColor);
        }
        dc.setColor(modeColor, Graphics.COLOR_TRANSPARENT);
        dc.drawText(startX + boltW + gap, cy, valFont, modeStr,
                    Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // Ride-mode colour, cool -> hot by mode number. Edit to taste / re-map.
    private function modeColorFor(index as Number?) as Graphics.ColorType {
        if (index == null) { return Graphics.COLOR_WHITE; }
        var ramp = [
            Graphics.COLOR_GREEN,    // 0
            Graphics.COLOR_GREEN,    // 1
            Graphics.COLOR_GREEN,    // 2
            Graphics.COLOR_YELLOW,   // 3
            Graphics.COLOR_ORANGE,   // 4
            Graphics.COLOR_RED,      // 5
            Graphics.COLOR_RED       // 6
        ];
        if (index >= 0 && index < ramp.size()) { return ramp[index]; }
        return Graphics.COLOR_WHITE;
    }

    private function isAllDigits(s as String) as Boolean {
        if (s.length() == 0) { return false; }
        var arr = s.toCharArray();
        for (var i = 0; i < arr.size(); i++) {
            var code = arr[i].toNumber();
            if (code < 48 || code > 57) { return false; }
        }
        return true;
    }

    // Draw a filled lightning bolt inside the box (x, y, w, h). Drawn as a
    // polygon because device fonts don't reliably contain a bolt glyph/emoji.
    private function drawBolt(dc as Graphics.Dc, x as Number, y as Number,
                             w as Number, h as Number, color as Graphics.ColorType) as Void {
        var pts = [
            [0.52, 0.00], [0.16, 0.56], [0.44, 0.56], [0.28, 1.00],
            [0.84, 0.40], [0.56, 0.40], [0.72, 0.00]
        ];
        var poly = [];
        for (var i = 0; i < pts.size(); i++) {
            var px = (x + pts[i][0] * w).toNumber();
            var py = (y + pts[i][1] * h).toNumber();
            poly.add([px, py]);
        }
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.fillPolygon(poly);
    }

    // Largest font from `ladder` whose height <= maxH and rendered width of
    // `text` <= maxW; falls back to the smallest in the ladder.
    private function bestFont(dc as Graphics.Dc, text as String,
                              maxW as Number, maxH as Number,
                              ladder as Array) as Graphics.FontType {
        for (var i = 0; i < ladder.size(); i++) {
            var f = ladder[i];
            if (dc.getFontHeight(f) <= maxH && dc.getTextWidthInPixels(text, f) <= maxW) {
                return f;
            }
        }
        return ladder[ladder.size() - 1];
    }

    // Y for the header (top area). Small fields drop the header off-screen-ish
    // by keeping it snug to the top.
    private function headerY(dc as Graphics.Dc, font as Graphics.FontType) as Number {
        return 1;
    }

    // Vertical center for the big values, leaving room for the header.
    private function valueCenterY(dc as Graphics.Dc) as Number {
        var headerH = dc.getFontHeight(pickFont(dc, false));
        var top = headerH + 1;
        return top + (_h - top) / 2;
    }

    // Choose a value/label font sized to the field. Larger fields get larger
    // fonts. `big` selects the value font; otherwise the label font.
    private function pickFont(dc as Graphics.Dc, big as Boolean) as Graphics.FontType {
        if (big) {
            if (_h >= 130) { return Graphics.FONT_NUMBER_MEDIUM; }
            if (_h >= 90)  { return Graphics.FONT_NUMBER_MILD; }
            if (_h >= 55)  { return Graphics.FONT_LARGE; }
            return Graphics.FONT_MEDIUM;
        } else {
            if (_h >= 90) { return Graphics.FONT_TINY; }
            return Graphics.FONT_XTINY;
        }
    }

    // Step a font down until `text` fits within maxWidth, floor at XTINY.
    private function fitTextWidth(dc as Graphics.Dc, text as String,
                                  maxWidth as Number, start as Graphics.FontType) as Graphics.FontType {
        var ladder = [
            Graphics.FONT_NUMBER_MEDIUM,
            Graphics.FONT_NUMBER_MILD,
            Graphics.FONT_LARGE,
            Graphics.FONT_MEDIUM,
            Graphics.FONT_SMALL,
            Graphics.FONT_TINY,
            Graphics.FONT_XTINY
        ];
        // Find our start position in the ladder.
        var i = 0;
        for (var k = 0; k < ladder.size(); k++) {
            if (ladder[k] == start) { i = k; break; }
        }
        for (; i < ladder.size(); i++) {
            if (dc.getTextWidthInPixels(text, ladder[i]) <= maxWidth) {
                return ladder[i];
            }
        }
        return Graphics.FONT_XTINY;
    }
}
