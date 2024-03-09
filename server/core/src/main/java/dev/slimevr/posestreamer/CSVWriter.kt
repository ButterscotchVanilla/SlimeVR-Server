package dev.slimevr.posestreamer

import dev.slimevr.tracking.processor.Bone
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.Tracker
import io.eiren.util.collections.FastList
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

	override fun writeHeader(skeleton: HumanSkeleton, streamer: PoseStreamer) {
		// Tracker columns
		columns.add(TrackerColumn(skeleton.headTracker!!))
		columns.add(TrackerColumn(skeleton.leftHandTracker!!))
		columns.add(TrackerColumn(skeleton.rightHandTracker!!))

		// Bone columns
		columns.add(BoneColumn(skeleton.headBone))
		columns.add(BoneColumn(skeleton.neckBone))

		columns.add(BoneColumn(skeleton.leftShoulderBone))
		columns.add(BoneColumn(skeleton.leftUpperArmBone))
		columns.add(BoneColumn(skeleton.leftLowerArmBone))
		columns.add(BoneColumn(skeleton.leftHandBone))

		columns.add(BoneColumn(skeleton.rightShoulderBone))
		columns.add(BoneColumn(skeleton.rightUpperArmBone))
		columns.add(BoneColumn(skeleton.rightLowerArmBone))
		columns.add(BoneColumn(skeleton.rightHandBone))

		// Write CSV header
		writer.write("${columns.joinToString(",") { it.toColumnLabels() }}\n")
	}

	override fun writeFrame(skeleton: HumanSkeleton) {
		writer.write("${columns.joinToString(",") { it.dataToColumns() }}\n")
	}

	override fun close() {
		writer.close()
		super.close()
	}

	interface CSVColumn {
		fun toColumnLabels(): String
		fun dataToColumns(): String
	}

	data class TrackerColumn(val tracker: Tracker) : CSVColumn {
		override fun toColumnLabels(): String {
			val name = "tracker ${tracker.trackerPosition?.name ?: "unknown"}"

			val sb = StringBuilder()

			if (tracker.hasRotation) {
				sb.append("$name quat w,$name quat x,$name quat y,$name quat z")
			}

			if (tracker.hasPosition) {
				sb.append("$name pos x,$name pos y,$name pos z")
			}

			return sb.toString()
		}

		override fun dataToColumns(): String {
			val sb = StringBuilder()

			if (tracker.hasRotation) {
				val rot = tracker.getRotation()
				sb.append("${rot.w},${rot.x},${rot.y},${rot.z}")
			}

			if (tracker.hasPosition) {
				val pos = tracker.position
				sb.append("${pos.x},${pos.y},${pos.z}")
			}

			return sb.toString()
		}
	}

	data class BoneColumn(val bone: Bone) : CSVColumn {
		override fun toColumnLabels(): String {
			val name = "bone ${bone.boneType.name}"
			return "$name quat w,$name quat x,$name quat y,$name quat z"
		}

		override fun dataToColumns(): String {
			val rot = bone.getGlobalRotation()
			return "${rot.w},${rot.x},${rot.y},${rot.z}"
		}
	}
}
