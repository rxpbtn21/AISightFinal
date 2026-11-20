package com.example.aisightmobile;

/**
 * One thing seen in the upright camera frame. Deliberately plain Java (no android.graphics)
 * so the announcement logic can be unit-tested on a normal JVM.
 */
final class Detection {
    final String label;          // model class name, or Lexicon.WALL for the depth-based wall alert
    final float confidence;
    final int classId;
    final float left, top, right, bottom;   // pixels in the upright frame
    float meters = Float.POSITIVE_INFINITY; // from the depth map; infinite/NaN = unknown
    String pos3 = "straight ahead";
    String pos5 = "front";
    float priority;

    Detection(String label, float confidence, int classId, float left, float top, float right, float bottom) {
        this.label = label;
        this.confidence = confidence;
        this.classId = classId;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    float width()   { return Math.max(0f, right - left); }
    float height()  { return Math.max(0f, bottom - top); }
    float centerX() { return (left + right) * 0.5f; }
    float area()    { return width() * height(); }

    boolean hasDistance() { return !Float.isNaN(meters) && !Float.isInfinite(meters); }
}
