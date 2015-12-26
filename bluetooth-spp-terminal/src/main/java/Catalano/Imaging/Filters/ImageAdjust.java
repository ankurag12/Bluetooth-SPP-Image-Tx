package Catalano.Imaging.Filters;

import java.util.ArrayList;

import Catalano.Imaging.FastBitmap;
import Catalano.Imaging.IBaseInPlace;
import Catalano.Imaging.Tools.ImageHistogram;
import Catalano.Imaging.Tools.ImageStatistics;

// Catalano Android Imaging Library
// The Catalano Framework
//
// Copyright © Diego Catalano, 2015
// diego.catalano at live.com
//
//    This library is free software; you can redistribute it and/or
//    modify it under the terms of the GNU Lesser General Public
//    License as published by the Free Software Foundation; either
//    version 2.1 of the License, or (at your option) any later version.
//
//    This library is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
//    Lesser General Public License for more details.
//
//    You should have received a copy of the GNU Lesser General Public
//    License along with this library; if not, write to the Free Software
//    Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
//


/**
 * Adaptive Contrast Enhancement is modification of the gray level values based on some criterion
 * that adjusts its parameters as local image characteristics change.
 *
 * @author Diego Catalano
 */
public class ImageAdjust implements IBaseInPlace {

    private double lin[];
    private double lowIn;
    private double highIn;
    private double lowOut;
    private double highOut;
    private double gamma;

    // tolerence for stretchlim
    private double tol_low;
    private double tol_high;

    public ImageAdjust() {
        this.lin = new double[256];
        this.linspace();
        this.lowIn = 0.3059;
        this.highIn = 0.6314;
        this.lowOut = 0;
        this.highOut = 1;
        this.gamma = 1;
        this.tol_high = 0.99;
        this.tol_low = 0.01;

    }

    private void linspace() {
        double space = (double) 1 / 255;
        for (int i = 0; i < 256; i++) {
            this.lin[i] = i * space;
        }
    }

    private void adjustArray() {
        // Make sure the Array is in range [Lin:Hin]
        for (int i = 0; i < 256; i++) {
            if (lin[i] < lowIn) {
                lin[i] = lowIn;
            } else if (lin[i] > highIn) {
                lin[i] = highIn;
            }

            lin[i] = ((lin[i] - lowIn) / (highIn - lowIn)) * gamma;
            lin[i] = 255 * ((lin[i] * (highOut - lowOut)) + lowOut);
        }
    }


    @Override
    public void applyInPlace(FastBitmap fastBitmap) {

        int width = fastBitmap.getWidth();
        int height = fastBitmap.getHeight();

        stretchlim(fastBitmap);
        adjustArray();
        int intensity;
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {

                intensity = fastBitmap.getGray(i, j);
                fastBitmap.setGray(i, j, (int) lin[intensity]);
            }
        }
    }

    private void stretchlim(FastBitmap fastBitmap) {
        ImageStatistics stat = new ImageStatistics(fastBitmap);
        ArrayList<int[]> imageHist = new ArrayList<int[]>();
        imageHist.add(stat.getHistogramGray().getValues());

        ImageHistogram imHist = new ImageHistogram(stat.getHistogramGray().getValues());

        double cdf[] = new double[256];
        cdf = imHist.CDF(imHist);

        int lowPos = 255;
        int highPos = 0;

        for (int i = 0; i < 256; i++) {
            if (255 == lowPos) {
                if (cdf[i] > tol_low) {
                    lowPos = i;
                }
            }

            if (0 == highPos) {

                if (cdf[i] > tol_high) {
                    highPos = i;
                }
            }
        }

        lowIn = (double) (lowPos - 1) / 255;
        highIn = (double) (highPos - 1) / 255;
    }
}
