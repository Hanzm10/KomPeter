package com.github.ragudos.kompeter.app.desktop.assets;

import java.awt.Color;

import javax.swing.UIManager;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.ColorFunctions;

public class SVGIconUIColor extends FlatSVGIcon {

	private String colorKey;
	private float alpha;

	public SVGIconUIColor(String name, float scale, String colorKey) {
		this(name, scale, colorKey, 1f);
	}

	public SVGIconUIColor(String name, float scale, String colorKey, float alpha) {
		super(name, scale);
		this.colorKey = colorKey;
		this.alpha = alpha;

		setColorFilter(new ColorFilter(color -> {
			Color uiColor = UIManager.getColor(getColorKey());

			if (uiColor != null) {
				return getAlpha() == 1 ? uiColor : ColorFunctions.fade(uiColor, getAlpha());
			}

			return color;
		}));
	}

	public String getColorKey() {
		return colorKey;
	}

	public void setColorKey(String colorKey) {
		this.colorKey = colorKey;

		setColorFilter(new ColorFilter(color -> {
			Color uiColor = UIManager.getColor(getColorKey());

			if (uiColor != null) {
				return getAlpha() == 1 ? uiColor : ColorFunctions.fade(uiColor, getAlpha());
			}

			return color;
		}));
	}

	public float getAlpha() {
		return alpha;
	}

	public void setAlpha(float alpha) {
		this.alpha = alpha;
	}
}
