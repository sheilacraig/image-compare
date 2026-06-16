package com.imagecompare;

import nu.pattern.OpenCV;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DebugContour {
    static { OpenCV.loadLocally(); }

    public static void main(String[] args) {
        ImagePreprocessor p = new ImagePreprocessor();
        ImagePreprocessor.PreprocessResult r1 = p.preprocess("images/1.png");
        ImagePreprocessor.PreprocessResult r2 = p.preprocess("images/2.png");

        List<MatOfPoint> c1 = findContours(r1.binary);
        List<MatOfPoint> c2 = findContours(r2.binary);

        System.out.println("Contours found: img1=" + c1.size() + " img2=" + c2.size());

        if (!c1.isEmpty() && !c2.isEmpty()) {
            double d1 = Imgproc.matchShapes(c1.get(0), c2.get(0), Imgproc.CONTOURS_MATCH_I1, 0);
            double d2 = Imgproc.matchShapes(c1.get(0), c2.get(0), Imgproc.CONTOURS_MATCH_I2, 0);
            double d3 = Imgproc.matchShapes(c1.get(0), c2.get(0), Imgproc.CONTOURS_MATCH_I3, 0);
            System.out.println("matchShapes distances: I1=" + d1 + " I2=" + d2 + " I3=" + d3);

            double area1 = Imgproc.contourArea(c1.get(0));
            double area2 = Imgproc.contourArea(c2.get(0));
            System.out.println("Contour areas: c1=" + area1 + " c2=" + area2 +
                    " ratio=" + Math.min(area1, area2) / Math.max(area1, area2));

            // Test different decay functions
            System.out.println("\nDecay function tests for I1=" + d1 + ":");
            System.out.println("  1/(1+3*d)  = " + (1.0 / (1.0 + 3.0 * d1)));
            System.out.println("  1/(1+10*d) = " + (1.0 / (1.0 + 10.0 * d1)));
            System.out.println("  exp(-2*d)  = " + Math.exp(-2.0 * d1));
            System.out.println("  exp(-5*d)  = " + Math.exp(-5.0 * d1));

            System.out.println("\nDecay function tests for I2=" + d2 + ":");
            System.out.println("  1/(1+d)    = " + (1.0 / (1.0 + d2)));
            System.out.println("  1/(1+0.5*d)= " + (1.0 / (1.0 + 0.5 * d2)));
            System.out.println("  exp(-0.5*d)= " + Math.exp(-0.5 * d2));

            System.out.println("\nDecay function tests for I3=" + d3 + ":");
            System.out.println("  1/(1+5*d)  = " + (1.0 / (1.0 + 5.0 * d3)));
            System.out.println("  1/(1+d)    = " + (1.0 / (1.0 + d3)));
        }

        r1.release();
        r2.release();
    }

    static List<MatOfPoint> findContours(Mat binary) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(binary.clone(), contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        hierarchy.release();
        contours.sort(Comparator.comparingDouble(c -> -Imgproc.contourArea(c)));
        return contours;
    }
}
