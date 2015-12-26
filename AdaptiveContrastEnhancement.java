package ru.sash0k.bluetooth_terminal.image;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.ImageView;

import ru.sash0k.bluetooth_terminal.R;
import ru.sash0k.bluetooth_terminal.Utils;

/**
 * Created by rv on 12/25/15.
 */
public class AdaptiveContrastEnhancement {

    /**
     * Adaptive Contrast Enhancement is modification of the gray level values based on some
     * criterion
     * that adjusts its parameters as local image characteristics change.
     *
     * @author Diego Catalano
     */

    int windowSize;
    double k1, k2, maxGain, minGain;

    /**
     * Initialize a new instance of the AdaptiveContrastEnhancement class.
     *
     * @param windowSize Size of window(should be an odd number).
     * @param k1         Local gain factor, between 0 and 1.
     * @param k2         Local mean constant, between 0 and 1.
     * @param minGain    The minimum gain factor.
     * @param maxGain    The maximum gain factor.
     */
    public AdaptiveContrastEnhancement(int windowSize, double k1, double k2, double minGain,
                                       double maxGain) {
        this.windowSize = windowSize;
        this.k1 = k1;
        this.k2 = k2;
        this.minGain = minGain;
        this.maxGain = maxGain;
    }

    public Bitmap applyInPlace(Bitmap Bitmap) {

        int width = Bitmap.getWidth();
        int height = Bitmap.getHeight();
        int lines = CalcLines(windowSize);

        Bitmap bmp2 = Bitmap.copy(Bitmap.getConfig(), true);

        // the mean (average) for the entire image I(x,y);
        double mean = getMean(Bitmap);
        int colour;
        for (int x = 0; x < height; x++) {
            for (int y = 0; y < width; y++) {

                int hits = 0;
                int windowSize2 = windowSize * windowSize;
                int[] values = new int[windowSize2];

                double sumMean = 0;
                double sumVar = 0;
                double factor;

                for (int i = x - lines; i <= x + lines; i++) {
                    for (int j = y - lines; j <= y + lines; j++) {

                        if ((i >= 0) && (i < height) && (j >= 0) && (j < width)) {
                            values[hits] = getGray(bmp2, i, j);

                            //sumGray += values[hits];
                            sumMean += values[hits];
                            sumVar += values[hits] * values[hits];
                            hits++;
                        }
                    }
                }

                sumMean /= windowSize2;
                sumVar /= windowSize2;
                sumVar -= sumMean * sumMean;

                if (sumVar != 0)
                    factor = k1 * (mean / sumVar);
                else
                    factor = maxGain;

                if (factor > maxGain) factor = maxGain;
                if (factor < minGain) factor = minGain;

                double gray = factor * (getGray(bmp2, x, y) - sumMean) + k2 * sumMean;
                //Utils.log("Gray values "+x +" "+y+" before: "+getGray(Bitmap,x,y)+" after:
                // "+gray);

                setGray(Bitmap, x, y, (int) gray);

            }
        }


        return Bitmap;
    }


    /**
     * Get Gray.
     *
     * @param x X axis coordinate.
     * @param y Y axis coordinate.
     * @return Gray channel's value.
     */
    public int getGray(Bitmap bm, int x, int y) {
        return Color.red(bm.getPixel(y, x));
    }

    /**
     * Set Gray.
     *
     * @param x     X axis coordinate.
     * @param y     Y axis coordinate.
     * @param value Gray channel's value.
     */
    public void setGray(Bitmap bm, int x, int y, int value) {
        int color = Color.rgb(value, value, value);
        bm.setPixel(y, x, color);
    }

    private double getMean(Bitmap Bitmap) {

        int sum = 0;
        for (int i = 0; i < Bitmap.getHeight(); i++) {
            for (int j = 0; j < Bitmap.getWidth(); j++) {
                sum += getGray(Bitmap, i, j);
            }
        }

        return sum / (Bitmap.getWidth() * Bitmap.getHeight());
    }

    private int CalcLines(int windowSize) {
        return (windowSize - 1) / 2;
    }

}

