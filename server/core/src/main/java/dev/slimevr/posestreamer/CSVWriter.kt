package dev.slimevr.posestreamer

import dev.slimevr.tracking.processor.Bone
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker
import io.eiren.util.collections.FastList
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter

class CSVWriter : PoseDataStream {

	private val writer = BufferedWriter(OutputStreamWriter(outputStream), 4096)
	private val columns = FastList<CSVColumn>()

	constructor(outputStream: OutputStream) : super(outputStream)
	constructor(file: File) : super(file)
	constructor(file: String) : super(file)

	private fun addTracker(tracker: Tracker) {
		if (!tracker.hasRotation || !tracker.hasPosition) {
			throw IllegalStateException("Tracker ${tracker.trackerPosition} must have rotation and position data.")
		}
		columns.add(TrackerColumn(tracker))
	}

	private fun addBoneHierarchy(bone: Bone) {
		columns.add(BoneColumn(bone))

		for (child in bone.children) {
			addBoneHierarchy(child)
		}
	}

	override fun writeHeader(skeleton: HumanSkeleton, streamer: PoseStreamer) {
		// Tracker columns
		addTracker(skeleton.headTracker!!)
		addTracker(skeleton.leftHandTracker!!)
		addTracker(skeleton.rightHandTracker!!)

		// Bone columns
		if (skeleton.isTrackingLeftArmFromController || skeleton.isTrackingRightArmFromController) {
			throw IllegalStateException("Arms cannot be tracked from controllers.")
		}
		addBoneHierarchy(skeleton.headBone)

		// Write CSV header
		writer.write("${columns.joinToString(",") { it.toColumnLabels() }}\n")
	}

	override fun writeFrame(skeleton: HumanSkeleton) {
		val rotOffset = skeleton.headTracker!!.getRotation().inv().unit()
		val posOffset = -skeleton.headTracker!!.position
		writer.write("${columns.joinToString(",") { it.dataToColumns(rotOffset, posOffset) }}\n")
	}

	override fun close() {
		writer.close()
		super.close()
	}

	interface CSVColumn {
		fun toColumnLabels(): String
		fun dataToColumns(rotOffset: Quaternion, posOffset: Vector3): String
	}

	data class TrackerColumn(val tracker: Tracker) : CSVColumn {
		override fun toColumnLabels(): String {
			val name = "tracker ${tracker.trackerPosition?.name ?: "unknown"}"
			return "$name quat w,$name quat x,$name quat y,$name quat z,$name pos x,$name pos y,$name pos z"
		}

		override fun dataToColumns(rotOffset: Quaternion, posOffset: Vector3): String {
			val rot = (rotOffset * tracker.getRotation()).unit()
			val pos = posOffset + tracker.position
			return "${rot.w},${rot.x},${rot.y},${rot.z},${pos.x},${pos.y},${pos.z}"
		}
	}

	data class BoneColumn(val bone: Bone) : CSVColumn {
		override fun toColumnLabels(): String {
			val name = "bone ${bone.boneType.name}"
			return "$name quat w,$name quat x,$name quat y,$name quat z"
		}

		override fun dataToColumns(rotOffset: Quaternion, posOffset: Vector3): String {
			val rot = (rotOffset * bone.getGlobalRotation()).unit()
			return "${rot.w},${rot.x},${rot.y},${rot.z}"
		}
	}
}
