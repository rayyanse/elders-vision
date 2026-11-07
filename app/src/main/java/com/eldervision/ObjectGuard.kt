package com.eldervision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

data class SeenObject(val name: String, val left: Float, val right: Float, val bottom: Float)

class ObjectGuard(context: Context) {
    private val detector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(Config.OBJECT_MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(10)
            .setScoreThreshold(Config.OBJECT_MIN_SCORE)
            .build(),
    )

    fun detect(frame: Bitmap): List<SeenObject> {
        val result = detector.detect(BitmapImageBuilder(frame).build())
        return result.detections().mapNotNull { detection ->
            val name = detection.categories().firstOrNull()?.categoryName() ?: return@mapNotNull null
            if (name !in Config.OBSTACLE_CLASSES) return@mapNotNull null
            val box = detection.boundingBox()
            val ownBody = name == "person" && box.bottom / frame.height > Config.OWN_BODY_BOTTOM && box.top / frame.height > Config.OWN_BODY_TOP
            if (ownBody) return@mapNotNull null
            SeenObject(name, box.left / frame.width, box.right / frame.width, box.bottom / frame.height)
        }
    }

    fun close() {
        detector.close()
    }
}
