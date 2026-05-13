package com.pixelhero.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Skeleton model for character animation. Each character has up to 15 joints
 * laid out as a humanoid.
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

        fun humanoidTemplate(x0: Int, y0: Int, x1: Int, y1: Int): Skeleton {
            val s = Skeleton()
            val w = (x1 - x0 + 1).toFloat()
            val h = (y1 - y0 + 1).toFloat()
            val cx = (x0 + x1) / 2f
            val headY = y0 + h * 0.10f
            val neckY = y0 + h * 0.22f
            val shoulderY = y0 + h * 0.26f
            val elbowY = y0 + h * 0.42f
            val handY = y0 + h * 0.55f
            val hipY = y0 + h * 0.55f
            val kneeY = y0 + h * 0.78f
            val footY = y0 + h * 0.95f
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
 * Per-pixel binding to the K nearest joints with inverse-distance weights.
 *
 * Each pixel's offset during animation is computed as a WEIGHTED AVERAGE of
 * the K nearest joints' offsets, instead of being driven by a single nearest
 * joint. This gives smooth deformation with no visible seams at body part
 * boundaries (the issue with hard 1-joint-per-pixel binding).
 *
 * K = 1: behaves like the old hard binding (used for comparison)
 * K = 3: typical sweet spot — smooth but still snappy
 * K = 5: very smooth but limbs may "blur" into each other
 */
class PixelSkin(val width: Int, val height: Int, skeleton: Skeleton, val k: Int = 3) {
    val jointOrder: List<JointType> = skeleton.joints.keys.toList()

    /** assignment[(y * w + x)] = index of NEAREST joint (kept for backward compat). */
    val assignment: IntArray = IntArray(width * height)

    /** Per-pixel K nearest joint indices, packed as [K * pixelIdx + k]. */
    val nearestIdx: IntArray = IntArray(width * height * k)

    /** Per-pixel K weights (inverse distance squared, normalized). */
    val nearestWeight: FloatArray = FloatArray(width * height * k)

    init {
        val joints = jointOrder.map { skeleton.joints[it]!! }
        val nJ = joints.size
        if (nJ == 0) {
            // No joints: leave arrays at zero (assignment 0 / no weights)
        } else {
            val kEff = k.coerceAtMost(nJ)
            // Reusable arrays for K-NN per pixel
            val dist = FloatArray(nJ)
            val idx = IntArray(nJ)
            for (y in 0 until height) for (x in 0 until width) {
                // Compute squared distance to every joint
                for (i in 0 until nJ) {
                    val dx = x - joints[i].x; val dy = y - joints[i].y
                    dist[i] = dx * dx + dy * dy
                    idx[i] = i
                }
                // Partial selection sort: pick K smallest
                for (i in 0 until kEff) {
                    var minK = i
                    for (j in i + 1 until nJ) if (dist[j] < dist[minK]) minK = j
                    if (minK != i) {
                        val td = dist[i]; dist[i] = dist[minK]; dist[minK] = td
                        val ti = idx[i]; idx[i] = idx[minK]; idx[minK] = ti
                    }
                }
                val pixIdx = y * width + x
                assignment[pixIdx] = idx[0]
                // Inverse-distance weights, normalized
                var sum = 0f
                for (kk in 0 until kEff) {
                    val w = 1f / (dist[kk] + 0.5f)
                    nearestWeight[pixIdx * k + kk] = w
                    sum += w
                }
                for (kk in 0 until kEff) {
                    nearestIdx[pixIdx * k + kk] = idx[kk]
                    nearestWeight[pixIdx * k + kk] /= sum
                }
                // Fill remaining slots if k > nJ
                for (kk in kEff until k) {
                    nearestIdx[pixIdx * k + kk] = idx[0]
                    nearestWeight[pixIdx * k + kk] = 0f
                }
            }
        }
    }
}
