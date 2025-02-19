package dev.slimevr.autobone

import dev.slimevr.autobone.AutoBone.TrackerAdjustments
import dev.slimevr.autobone.AutoBone.TrackerCalibration
import dev.slimevr.config.AutoBoneConfig
import dev.slimevr.config.ConfigManager
import dev.slimevr.poseframeformat.PoseFrames
import dev.slimevr.poseframeformat.player.TrackerFramesPlayer
import dev.slimevr.tracking.processor.HumanPoseManager
import io.eiren.util.collections.FastList
import java.util.function.Consumer

class AutoBoneStep(
	val config: AutoBoneConfig,
	val targetHmdHeight: Float,
	val frames: PoseFrames,
	val epochCallback: Consumer<AutoBone.Epoch>?,
	serverConfig: ConfigManager,
	var curEpoch: Int = 0,
	var curAdjustRate: Float = 0f,
	var cursor1: Int = 0,
	var cursor2: Int = 0,
	var currentHmdHeight: Float = 0f,
) {
	var maxFrameCount = frames.maxFrameCount

	val framePlayer1 = TrackerFramesPlayer(frames)
	val framePlayer2 = TrackerFramesPlayer(frames)

	val trackerAdj = FastList<TrackerAdjustments>()

	val trackers1 = framePlayer1.trackers.toList()
	val trackers2 = framePlayer2.trackers.toList()

	val skeleton1 = HumanPoseManager(trackers1)
	val skeleton2 = HumanPoseManager(trackers2)

	val errorStats = StatsCalculator()

	init {
		// Load server configs into the skeleton
		skeleton1.loadFromConfig(serverConfig)
		skeleton2.loadFromConfig(serverConfig)
		// Disable leg tweaks and IK solver, these will mess with the resulting positions
		skeleton1.setLegTweaksEnabled(false)
		skeleton2.setLegTweaksEnabled(false)

		for (tracker1 in framePlayer1.playerTrackers) {
			val tp = tracker1.tracker.trackerPosition ?: continue
			val tracker2 = framePlayer2.playerTrackers.find { it.tracker.trackerPosition == tp } ?: continue

			trackerAdj.add(
				TrackerAdjustments(
					tracker1,
					tracker2,
					TrackerCalibration(tp),
				),
			)
		}
	}

	fun setCursors(cursor1: Int, cursor2: Int, updatePlayerCursors: Boolean) {
		this.cursor1 = cursor1
		this.cursor2 = cursor2

		if (updatePlayerCursors) {
			updatePlayerCursors()
		}
	}

	fun updatePlayerCursors() {
		framePlayer1.setCursors(cursor1)
		framePlayer2.setCursors(cursor2)
		skeleton1.update()
		skeleton2.update()
	}

	val heightOffset: Float
		get() = targetHmdHeight - currentHmdHeight
}
