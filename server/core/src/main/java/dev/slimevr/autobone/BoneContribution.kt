package dev.slimevr.autobone

import dev.slimevr.autobone.AutoBone.Companion.MIN_SLIDE_DIST
import dev.slimevr.autobone.AutoBone.Companion.SYMM_CONFIGS
import dev.slimevr.config.AutoBoneConfig
import dev.slimevr.config.ConfigManager
import dev.slimevr.poseframeformat.PoseFrames
import dev.slimevr.tracking.processor.BoneType
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.trackers.TrackerRole
import io.github.axisangles.ktmath.Vector3

object BoneContribution {
	/**
	 * Computes the local tail position of the bone after rotation.
	 */
	fun getBoneLocalTail(
		skeleton: HumanPoseManager,
		boneType: BoneType,
	): Vector3 {
		val bone = skeleton.getBone(boneType)
		return bone.getTailPosition() - bone.getPosition()
	}

	/**
	 * Computes the direction of the bone tail's movement between skeletons 1 and 2.
	 */
	fun getBoneLocalTailDir(
		skeleton1: HumanPoseManager,
		skeleton2: HumanPoseManager,
		boneType: BoneType,
	): Vector3? {
		val boneOff = getBoneLocalTail(skeleton2, boneType) - getBoneLocalTail(skeleton1, boneType)
		val boneOffLen = boneOff.len()
		// If the offset is approx 0, just return null so it can be easily ignored
		return if (boneOffLen > MIN_SLIDE_DIST) boneOff / boneOffLen else null
	}

	/**
	 * Predicts how much the provided config should be affecting the slide offsets
	 * of the left and right ankles.
	 */
	fun getSlideDot(
		skeleton1: HumanPoseManager,
		skeleton2: HumanPoseManager,
		config: SkeletonConfigOffsets,
		slideL: Vector3?,
		slideR: Vector3?,
	): Float {
		var slideDot = 0f
		// Used for right offset if not a symmetric bone
		var boneOffL: Vector3? = null

		// Treat null as 0
		if (slideL != null) {
			boneOffL = getBoneLocalTailDir(skeleton1, skeleton2, config.affectedOffsets[0])

			// Treat null as 0
			if (boneOffL != null) {
				slideDot += slideL.dot(boneOffL)
			}
		}

		// Treat null as 0
		if (slideR != null) {
			// IMPORTANT: This assumption for acquiring BoneType only works if
			// SkeletonConfigOffsets is set up to only affect one BoneType, make sure no
			// changes to SkeletonConfigOffsets goes against this assumption, please!
			val boneOffR = if (SYMM_CONFIGS.contains(config)) {
				getBoneLocalTailDir(skeleton1, skeleton2, config.affectedOffsets[1])
			} else if (slideL != null) {
				// Use cached offset if slideL was used
				boneOffL
			} else {
				// Compute offset if missing because of slideL
				getBoneLocalTailDir(skeleton1, skeleton2, config.affectedOffsets[0])
			}

			// Treat null as 0
			if (boneOffR != null) {
				slideDot += slideR.dot(boneOffR)
			}
		}

		return slideDot / 2f
	}

	fun computeContributingBones(frames: PoseFrames, serverConfig: ConfigManager): Map<BoneType, StatsCalculator> {
		val boneMap = mutableMapOf<BoneType, StatsCalculator>()

		val config = AutoBoneConfig()
		config.calcInitError = false
		config.randomizeFrameOrder = true

		val step = PoseFrameStep<Unit>(
			config = config,
			serverConfig = serverConfig,
			frames = frames,
			onStep = { step ->
				val slideL = step.skeleton2.getComputedTracker(TrackerRole.LEFT_FOOT).position -
					step.skeleton1.getComputedTracker(TrackerRole.LEFT_FOOT).position
				val slideLLen = slideL.len()
				val slideLUnit: Vector3? = if (slideLLen > MIN_SLIDE_DIST) slideL / slideLLen else null

				val slideR = step.skeleton2.getComputedTracker(TrackerRole.RIGHT_FOOT).position -
					step.skeleton1.getComputedTracker(TrackerRole.RIGHT_FOOT).position
				val slideRLen = slideR.len()
				val slideRUnit: Vector3? = if (slideRLen > MIN_SLIDE_DIST) slideR / slideRLen else null

				for (bone in MID_BONES) {
					val stats = boneMap.getOrPut(bone.affectedOffsets[0]) { StatsCalculator() }
					stats.addValue(getSlideDot(step.skeleton1, step.skeleton2, bone, slideLUnit, slideRUnit))
				}

				for (bone in LEFT_BONES) {
					val stats = boneMap.getOrPut(bone) { StatsCalculator() }
					val offset = getBoneLocalTailDir(step.skeleton1, step.skeleton2, bone)
					stats.addValue(if (slideLUnit != null && offset != null) slideLUnit.dot(offset) else 0f)
				}

				for (bone in RIGHT_BONES) {
					val stats = boneMap.getOrPut(bone) { StatsCalculator() }
					val offset = getBoneLocalTailDir(step.skeleton1, step.skeleton2, bone)
					stats.addValue(if (slideRUnit != null && offset != null) slideRUnit.dot(offset) else 0f)
				}
			},
			data = Unit,
		)

		PoseFrameIterator.iterateFrames(step)

		return boneMap
	}

	val MID_BONES = arrayOf(
		SkeletonConfigOffsets.HEAD,
		SkeletonConfigOffsets.NECK,
		SkeletonConfigOffsets.UPPER_CHEST,
		SkeletonConfigOffsets.CHEST,
		SkeletonConfigOffsets.WAIST,
		SkeletonConfigOffsets.HIP,
	)
	val LEFT_BONES = arrayOf(BoneType.LEFT_HIP, BoneType.LEFT_UPPER_LEG, BoneType.LEFT_LOWER_LEG)
	val RIGHT_BONES = arrayOf(BoneType.RIGHT_HIP, BoneType.RIGHT_UPPER_LEG, BoneType.RIGHT_LOWER_LEG)
}
