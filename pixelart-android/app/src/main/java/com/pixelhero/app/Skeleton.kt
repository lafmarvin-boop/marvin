package com.pixelhero.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Skeleton model for character animation. Each character has up to 15 joints
 * laid out as a humanoid. The user places joints on the sprite, then animations
 * move each joint independently and pixels follow their nearest joint.
 */
enum class JointType(val displayName: String, val parent: JointType? = null) {
    NECK("Cou"),
    HEAD("Tête", NECK),
    SHOULDER_L("Épaule G", NECK),
    SHOULDER_R("Épaule D", NECK),
    ELBOW_L("Coude G", SHOULDER_L),
    ELBOW_R("Coude D", SHOULDER_R),
    HAND_L("Main G", ELBOW_L),
    HAND_R("Main D", ELBOW_R),
    HIP_CENTER("Hanches", NECK),
    HIP_L("Hanche G", HIP_CENTER),
    HIP_R("Hanche D", HIP_CENTER),
    KNEE_L("Genou G", HIP_L),
    KNEE_R("Genou D", HIP_R),
    FOOT_L("Pied G", KNEE_L),
    FOOT_R("Pied D", KNEE_R)
}

data class Joint(var type: JointType, var x: Float, var y: Float) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("t", type.name); put("x", x.toDouble()); put("y", y.toDouble())
    }
    companion object {
        fun fromJson(obj: JSONObject): Joint = Joint(
            JointType.valueOf(obj.getString("t")),
            obj.getDouble("x").toFloat(),
            obj.getDouble("y").toFloat()
        )
    }
}

class Skeleton {
    val joints: MutableMap<JointType, Joint> = mutableMapOf()

    fun get(type: JointType): Joint? = joints[type]

    fun set(type: JointType, x: Float, y: Float) {
        joints[type] = Joint(type, x, y)
    }

    fun isComplete(): Boolean = JointType.values().all { it in joints }

    fun copy(): Skeleton {
        val s = Skeleton()
        joints.forEach { (k, v) -> s.joints[k] = v.copy() }
        return s
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        joints.values.forEach { arr.put(it.toJson()) }
        return JSONObject().apply { put("joints", arr) }
    }

    companion object {
        fun fromJson(obj: JSONObject): Skeleton {
            val s = Skeleton()
            val arr = obj.optJSONArray("joints") ?: return s
            for (i in 0 until arr.length()) {
                val j = Joint.fromJson(arr.getJSONObject(i))
                s.joints[j.type] = j
            }
            return s
        }

        /**
         * Auto-place joints assuming a humanoid character fills the bbox.
         * Standard proportions: head ~1/4 height, torso ~3/8, legs ~3/8.
         */
        fun humanoidTemplate(x0: Int, y0: Int, x1: Int, y1: Int): Skeleton {
            val s = Skeleton()
            val w = (x1 - x0 + 1).toFloat()
            val h = (y1 - y0 + 1).toFloat()
            val cx = (x0 + x1) / 2f
            // Vertical landmarks
            val headY = y0 + h * 0.10f
            val neckY = y0 + h * 0.22f
            val shoulderY = y0 + h * 0.26f
            val elbowY = y0 + h * 0.42f
            val handY = y0 + h * 0.55f
            val hipY = y0 + h * 0.55f
            val kneeY = y0 + h * 0.78f
            val footY = y0 + h * 0.95f
            // Horizontal landmarks
            val shoulderXL = cx - w * 0.20f
            val shoulderXR = cx + w * 0.20f
            val handXL = cx - w * 0.25f
            val handXR = cx + w * 0.25f
            val hipXL = cx - w * 0.10f
            val hipXR = cx + w * 0.10f
            val footXL = cx - w * 0.12f
            val footXR = cx + w * 0.12f
            s.set(JointType.HEAD, cx, headY)
            s.set(JointType.NECK, cx, neckY)
            s.set(JointType.SHOULDER_L, shoulderXL, shoulderY)
            s.set(JointType.SHOULDER_R, shoulderXR, shoulderY)
            s.set(JointType.ELBOW_L, shoulderXL - w * 0.02f, elbowY)
            s.set(JointType.ELBOW_R, shoulderXR + w * 0.02f, elbowY)
            s.set(JointType.HAND_L, handXL, handY)
            s.set(JointType.HAND_R, handXR, handY)
            s.set(JointType.HIP_CENTER, cx, hipY)
            s.set(JointType.HIP_L, hipXL, hipY)
            s.set(JointType.HIP_R, hipXR, hipY)
            s.set(JointType.KNEE_L, hipXL, kneeY)
            s.set(JointType.KNEE_R, hipXR, kneeY)
            s.set(JointType.FOOT_L, footXL, footY)
            s.set(JointType.FOOT_R, footXR, footY)
            return s
        }
    }
}

/**
 * Maps each pixel of a frame to its nearest joint. Built once per project size
 * and joint configuration, then reused for all animations.
 */
class PixelSkin(val width: Int, val height: Int, skeleton: Skeleton) {
    val jointOrder: List<JointType> = skeleton.joints.keys.toList()
    val assignment: IntArray = IntArray(width * height)

    init {
        val joints = jointOrder.map { skeleton.joints[it]!! }
        for (y in 0 until height) for (x in 0 until width) {
            var bestDist = Float.MAX_VALUE; var bestIdx = 0
            for ((i, j) in joints.withIndex()) {
                val dx = x - j.x; val dy = y - j.y
                val d = dx * dx + dy * dy
                if (d < bestDist) { bestDist = d; bestIdx = i }
            }
            assignment[y * width + x] = bestIdx
        }
    }
}
