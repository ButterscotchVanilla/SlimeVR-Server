package dev.slimevr.autobone

import com.jme3.math.FastMath
import dev.slimevr.VRServer
import dev.slimevr.autobone.AutoBone.AutoBoneResults
import dev.slimevr.autobone.AutoBone.Companion.loadDir
import dev.slimevr.autobone.errors.AutoBoneException
import dev.slimevr.poseframeformat.PoseFrameIO
import dev.slimevr.poseframeformat.PoseFrames
import dev.slimevr.poseframeformat.PoseRecorder
import dev.slimevr.poseframeformat.PoseRecorder.RecordingProgress
import dev.slimevr.poseframeformat.trackerdata.TrackerFrame
import dev.slimevr.poseframeformat.trackerdata.TrackerFrameData
import dev.slimevr.poseframeformat.trackerdata.TrackerFrames
import dev.slimevr.posestreamer.BVHFileStream
import dev.slimevr.posestreamer.PoseFrameStreamer
import dev.slimevr.tracking.processor.BoneType
import dev.slimevr.tracking.processor.config.SkeletonConfigManager
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.trackers.TrackerPosition
import io.eiren.util.StringUtils
import io.eiren.util.collections.FastList
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.EulerAngles
import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.apache.commons.lang3.ArrayUtils
import org.apache.commons.lang3.tuple.Pair
import java.io.File
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.*

class AutoBoneHandler(private val server: VRServer) {
	private val poseRecorder: PoseRecorder = PoseRecorder(server)
	private val autoBone: AutoBone = AutoBone(server)

	private val recordingLock = ReentrantLock()
	private var recordingThread: Thread? = null
	private val saveRecordingLock = ReentrantLock()
	private var saveRecordingThread: Thread? = null
	private val autoBoneLock = ReentrantLock()
	private var autoBoneThread: Thread? = null

	private val listeners = CopyOnWriteArrayList<AutoBoneListener>()

	fun addListener(listener: AutoBoneListener) {
		listeners.add(listener)
	}

	fun removeListener(listener: AutoBoneListener) {
		listeners.removeIf { listener == it }
	}

	private fun announceProcessStatus(
		processType: AutoBoneProcessType,
		message: String? = null,
		current: Long = -1L,
		total: Long = -1L,
		eta: Float = -1f,
		completed: Boolean = false,
		success: Boolean = true,
	) {
		listeners.forEach {
			it.onAutoBoneProcessStatus(
				processType,
				message,
				current,
				total,
				eta,
				completed,
				success,
			)
		}
	}

	@Throws(AutoBoneException::class)
	private fun processFrames(frames: PoseFrames): AutoBoneResults = autoBone
		.processFrames(frames) { epoch ->
			listeners.forEach { listener -> listener.onAutoBoneEpoch(epoch) }
		}

	fun startProcessByType(processType: AutoBoneProcessType?): Boolean {
		when (processType) {
			AutoBoneProcessType.RECORD -> startRecording()

			AutoBoneProcessType.SAVE -> saveRecording()

			AutoBoneProcessType.PROCESS -> processRecording()

			else -> {
				return false
			}
		}
		return true
	}

	fun startRecording() {
		recordingLock.withLock {
			// Prevent running multiple times
			if (recordingThread != null) {
				return
			}
			recordingThread = thread(start = true) { startRecordingThread() }
		}
	}

	private fun startRecordingThread() {
		try {
			if (poseRecorder.isReadyToRecord) {
				announceProcessStatus(AutoBoneProcessType.RECORD, "Recording...")

				// region ### Debug processing options ###
				// Whether to skip the recording process to make debugging faster
				val skipRecording = true
				// endregion

				// region ### Debug processing ###
				if (skipRecording) {
					announceProcessStatus(
						AutoBoneProcessType.RECORD,
						"Skipped recording.",
						completed = true,
						success = true,
					)
					return
				}
				// endregion

				// ex. 1000 samples at 20 ms per sample is 20 seconds
				val sampleCount = autoBone.globalConfig.sampleCount
				val sampleRate = autoBone.globalConfig.sampleRateMs
				// Calculate total time in seconds
				val totalTime: Float = (sampleCount * sampleRate) / 1000f

				val framesFuture = poseRecorder
					.startFrameRecording(
						sampleCount,
						sampleRate,
					) { progress: RecordingProgress ->
						announceProcessStatus(
							AutoBoneProcessType.RECORD,
							current = progress.frame.toLong(),
							total = progress.totalFrames.toLong(),
							eta = totalTime - (progress.frame * totalTime / progress.totalFrames),
						)
					}
				val frames = framesFuture.get()
				LogManager.info("[AutoBone] Done recording!")

				// Save a recurring recording for users to send as debug info
				announceProcessStatus(AutoBoneProcessType.RECORD, "Saving recording...")
				autoBone.saveRecording(frames, "LastABRecording.pfr")
				if (autoBone.globalConfig.saveRecordings) {
					announceProcessStatus(
						AutoBoneProcessType.RECORD,
						"Saving recording (from config option)...",
					)
					autoBone.saveRecording(frames)
				}
				listeners.forEach { listener: AutoBoneListener -> listener.onAutoBoneRecordingEnd(frames) }
				announceProcessStatus(
					AutoBoneProcessType.RECORD,
					"Done recording!",
					completed = true,
					success = true,
				)
			} else {
				announceProcessStatus(
					AutoBoneProcessType.RECORD,
					"The server is not ready to record",
					completed = true,
					success = false,
				)
				LogManager.severe("[AutoBone] Unable to record...")
				return
			}
		} catch (e: Exception) {
			announceProcessStatus(
				AutoBoneProcessType.RECORD,
				"Recording failed: ${e.message}",
				completed = true,
				success = false,
			)
			LogManager.severe("[AutoBone] Failed recording!", e)
		} finally {
			recordingThread = null
		}
	}

	fun stopRecording() {
		if (poseRecorder.isRecording) {
			poseRecorder.stopFrameRecording()
		}
	}

	fun cancelRecording() {
		if (poseRecorder.isRecording) {
			poseRecorder.cancelFrameRecording()
		}
	}

	fun saveRecording() {
		saveRecordingLock.withLock {
			// Prevent running multiple times
			if (saveRecordingThread != null) {
				return
			}
			saveRecordingThread = thread(start = true) { saveRecordingThread() }
		}
	}

	private fun saveRecordingThread() {
		try {
			val framesFuture = poseRecorder.framesAsync
			if (framesFuture != null) {
				announceProcessStatus(AutoBoneProcessType.SAVE, "Waiting for recording...")
				val frames = framesFuture.get()
				check(frames.frameHolders.isNotEmpty()) { "Recording has no trackers." }
				check(frames.maxFrameCount > 0) { "Recording has no frames." }
				announceProcessStatus(AutoBoneProcessType.SAVE, "Saving recording...")
				autoBone.saveRecording(frames)
				announceProcessStatus(
					AutoBoneProcessType.SAVE,
					"Recording saved!",
					completed = true,
					success = true,
				)
			} else {
				announceProcessStatus(
					AutoBoneProcessType.SAVE,
					"No recording found",
					completed = true,
					success = false,
				)
				LogManager.severe("[AutoBone] Unable to save, no recording was done...")
				return
			}
		} catch (e: Exception) {
			announceProcessStatus(
				AutoBoneProcessType.SAVE,
				"Failed to save recording: ${e.message}",
				completed = true,
				success = false,
			)
			LogManager.severe("[AutoBone] Failed to save recording!", e)
		} finally {
			saveRecordingThread = null
		}
	}

	fun processRecording() {
		autoBoneLock.withLock {
			// Prevent running multiple times
			if (autoBoneThread != null) {
				return
			}
			autoBoneThread = thread(start = true) { processRecordingThread() }
		}
	}

	private fun processRecordingThread() {
		try {
			announceProcessStatus(AutoBoneProcessType.PROCESS, "Loading recordings...")
			val frameRecordings = autoBone.loadRecordings()
			if (!frameRecordings.isEmpty()) {
				LogManager.info("[AutoBone] Done loading frames!")
			} else {
				val framesFuture = poseRecorder.framesAsync
				if (framesFuture != null) {
					announceProcessStatus(AutoBoneProcessType.PROCESS, "Waiting for recording...")
					val frames = framesFuture.get()
					frameRecordings.add(Pair.of("<Recording>", frames))
				} else {
					announceProcessStatus(
						AutoBoneProcessType.PROCESS,
						"No recordings found...",
						completed = true,
						success = false,
					)
					LogManager
						.severe(
							"[AutoBone] No recordings found in \"${loadDir.path}\" and no recording was done...",
						)
					return
				}
			}
			announceProcessStatus(AutoBoneProcessType.PROCESS, "Processing recording(s)...")
			LogManager.info("[AutoBone] Processing frames...")
			val errorStats = StatsCalculator()
			val offsetStats = EnumMap<SkeletonConfigOffsets, StatsCalculator>(
				SkeletonConfigOffsets::class.java,
			)
			val skeletonConfigManagerBuffer = SkeletonConfigManager(false)
			for ((key, value) in frameRecordings) {
				LogManager.info("[AutoBone] Processing frames from \"$key\"...")
				val trackers = value.frameHolders

				val autoboneRecordingDir = File("AutoBone Recordings")
				autoboneRecordingDir.mkdirs()

				// region ### Debug processing options ###
				// Remove the first number of frames
				val trimFrames = false
				// Isolate specific frames
				val isolateFrames = false
				// Offset the recording position
				val offsetPositions = false
				// Rotate all trackers with
				val offsetRotations = false
				// Remove specific tracker positions
				val removeTrackersByPosition = false
				// Offset HMD timing
				val offsetHmdTiming = false
				// Check for equal rotations
				val checkForEqualRotations = false

				// Generate BVH for AutoBone recording
				val generateBvh = true
				// Use the AutoBone adjusted proportions for the BVH recording
				val useAdjustmentsForBvh = true
				// endregion

				// region ### Debug processing ###
				if (trimFrames) {
					val framesToTrim = 41
					val saveTrimmed = true
					for (tracker in trackers) {
						if (tracker == null) continue
						for (i in 0 until framesToTrim) {
							tracker.frames.removeAt(0)
						}
					}
					if (saveTrimmed) {
						PoseFrameIO
							.writeToFile(
								File(
									autoboneRecordingDir,
									"${key}_trimmed.pfr",
								),
								value,
							)
					}
				}

				// Isolate specific frames
				if (isolateFrames) {
					val framesToKeep = intArrayOf(
						166,
						370,
					)
					val saveIsolated = true
					for (tracker in trackers) {
						if (tracker == null) continue
						for (i in tracker.frames.size - 1 downTo 0) {
							if (ArrayUtils.contains(framesToKeep, i)) continue
							tracker.frames.removeAt(i)
						}
					}
					if (saveIsolated) {
						PoseFrameIO
							.writeToFile(
								File(
									autoboneRecordingDir,
									"${key}_isolated.pfr",
								),
								value,
							)
					}
				}

				// Offset the recording position
				if (offsetPositions) {
					val offset = Vector3(0.0641426444f, 1.94856906f, 1.15352106f)
					val saveOffset = true
					for (tracker in trackers) {
						if (tracker == null) continue
						for (i in 0 until tracker.frames.size) {
							val frame: TrackerFrame = tracker.tryGetFrame(i) ?: continue
							if (frame.hasPosition()) {
								// Create a new frame with the offset position
								val newFrame =
									TrackerFrame(
										frame.tryGetTrackerPosition(),
										frame.tryGetRotation(),
										frame.tryGetPosition()?.plus(offset),
										frame.tryGetAcceleration(),
										frame.tryGetRawRotation(),
									)

								// Replace the frame with an edited one
								tracker.frames.removeAt(i)
								tracker.frames.add(i, newFrame)
							}
						}
					}
					if (saveOffset) {
						PoseFrameIO
							.writeToFile(
								File(
									autoboneRecordingDir,
									"${key}_offset.pfr",
								),
								value,
							)
					}
				}

				// Rotate all trackers with exceptions
				if (offsetRotations) {
					val keepRotationPositions: Array<TrackerPosition> =
						arrayOf(
							TrackerPosition.HEAD,
							TrackerPosition.HIP,
							TrackerPosition.LEFT_FOOT,
							TrackerPosition.RIGHT_FOOT,
						)
					val rotation: Quaternion =
						EulerAngles(EulerOrder.YZX, 0f, FastMath.PI, 0f)
							.toQuaternion()
					val saveRotated = true
					for (tracker in trackers) {
						if (tracker == null) continue
						for (i in 0 until tracker.frames.size) {
							val frame: TrackerFrame = tracker.tryGetFrame(i) ?: continue
							if (frame.hasRotation() && !ArrayUtils
									.contains(
										keepRotationPositions,
										frame.tryGetTrackerPosition(),
									)
							) {
								// Create a new frame with the offset rotation
								val oldTrackerRotation = frame.tryGetRotation()
								val newTrackerRotation =
									if (oldTrackerRotation != null) {
										rotation.times(oldTrackerRotation)
											.times(rotation)
									} else {
										null
									}
								val newFrame =
									TrackerFrame(
										frame.tryGetTrackerPosition(),
										newTrackerRotation,
										frame.tryGetPosition(),
										frame.tryGetAcceleration(),
										frame.tryGetRawRotation(),
									)

								// Replace the frame with an edited one
								tracker.frames.removeAt(i)
								tracker.frames.add(i, newFrame)
							}
						}
					}
					if (saveRotated) {
						PoseFrameIO
							.writeToFile(
								File(
									autoboneRecordingDir,
									"${key}_rotated.pfr",
								),
								value,
							)
					}
				}

				// Remove specific tracker positions
				if (removeTrackersByPosition) {
					val positions: Array<TrackerPosition> = arrayOf(
						TrackerPosition.HIP,
					)
					val saveWithoutTrackers = true
					for (i in trackers.size - 1 downTo 0) {
						val tracker = trackers[i] ?: continue
						if (ArrayUtils.contains(
								positions,
								tracker.tryGetFirstNotNullFrame()
									?.tryGetTrackerPosition(),
							)
						) {
							trackers.removeAt(i)
							tracker.frames.clear()
						}
					}
					if (saveWithoutTrackers) {
						PoseFrameIO
							.writeToFile(
								File(
									autoboneRecordingDir,
									"${key}_without_trackers.pfr",
								),
								value,
							)
					}
				}

				// Offset HMD timing
				if (offsetHmdTiming) {
					val framesToOffset = 20
					val saveHmdTimingOffset = true
					val posFramesToOffset = abs(framesToOffset)
					for (tracker in trackers) {
						if (tracker == null ||
							// Use a logical XOR, if it's positive, it needs to skip all
							// but the HMD tracker, if it's negative, then it needs to
							// skip only the HMD
							(
								(framesToOffset >= 0) xor
									(
										tracker.tryGetFirstNotNullFrame()?.tryGetTrackerPosition() ==
											TrackerPosition.HEAD
										)
								)
						) {
							continue
						}
						for (i in 0 until posFramesToOffset) {
							tracker.frames.removeAt(0)
						}
					}
					if (saveHmdTimingOffset) {
						PoseFrameIO
							.writeToFile(
								File(
									autoboneRecordingDir,
									"${key}_hmd_timing_offset.pfr",
								),
								value,
							)
					}
				}

				// Check for equal rotations
				if (checkForEqualRotations) {
					for (frames in value) {
						val chest = frames.find { frame -> frame?.tryGetTrackerPosition() == TrackerPosition.CHEST } ?: continue
						val hip = frames.find { frame -> frame?.tryGetTrackerPosition() == TrackerPosition.HIP } ?: continue

						val chestRot = chest.tryGetRotation()
						val hipRot = hip.tryGetRotation()
						if (chestRot != null && chestRot == hipRot) {
							LogManager.info("Chest == hip rotation $chestRot $hipRot")
						} else {
							LogManager.info("Chest != hip rotation $chestRot $hipRot")
						}
					}
				}
				// endregion

				// Output tracker info for the recording
				printTrackerInfo(value.frameHolders)

				// Actually process the recording
				val autoBoneResults = processFrames(value)
				LogManager.info("[AutoBone] Done processing!")

				// region ### Debug BVH generation ###
				// Generate BVH for AutoBone recording
				if (generateBvh) {
					val streamer = PoseFrameStreamer(value)
					if (useAdjustmentsForBvh) {
						autoBoneResults.configValues.forEach { (key: SkeletonConfigOffsets, newLength: Float?) ->
							streamer.humanPoseManager.setOffset(key, newLength)
						}
					}
					for (offset in BoneType.values) {
						streamer.humanPoseManager.computeNodeOffset(offset)
					}
					val bvhFolder = File("BVH Recordings")
					bvhFolder.mkdirs()
					BVHFileStream(
						File(bvhFolder, "$key.bvh"),
					).use { bvhFileStream ->
						val sampleRate = autoBone.globalConfig.sampleRateMs
						streamer.setOutput(bvhFileStream, sampleRate)
						streamer.streamAllFrames()
						streamer.closeOutput()
					}
				}
				// endregion

				// #region Stats/Values
				// Accumulate height error
				errorStats.addValue(autoBoneResults.heightDifference)

				// Accumulate length values
				for (offset in autoBoneResults.configValues) {
					val statCalc = offsetStats.getOrPut(offset.key) {
						StatsCalculator()
					}
					// Multiply by 100 to get cm
					statCalc.addValue(offset.value * 100f)
				}

				// Calculate and output skeleton ratios
				skeletonConfigManagerBuffer.setOffsets(autoBoneResults.configValues)
				printSkeletonRatios(skeletonConfigManagerBuffer)

				LogManager.info("[AutoBone] Length values: ${autoBone.lengthsString}")
			}
			// Length value stats
			val averageLengthVals = StringBuilder()
			offsetStats.forEach { (key, value) ->
				if (averageLengthVals.isNotEmpty()) {
					averageLengthVals.append(", ")
				}
				averageLengthVals
					.append(key.configKey)
					.append(": ")
					.append(StringUtils.prettyNumber(value.mean, 2))
					.append(" (SD ")
					.append(StringUtils.prettyNumber(value.standardDeviation, 2))
					.append(")")
			}
			LogManager.info("[AutoBone] Average length values: $averageLengthVals")

			// Height error stats
			LogManager
				.info(
					"[AutoBone] Average height error: ${
						StringUtils.prettyNumber(errorStats.mean, 6)
					} (SD ${StringUtils.prettyNumber(errorStats.standardDeviation, 6)})",
				)
			// #endregion
			listeners.forEach { listener: AutoBoneListener -> listener.onAutoBoneEnd(autoBone.offsets) }
			announceProcessStatus(
				AutoBoneProcessType.PROCESS,
				"Done processing!",
				completed = true,
				success = true,
			)
		} catch (e: Exception) {
			announceProcessStatus(
				AutoBoneProcessType.PROCESS,
				"Processing failed: ${e.message}",
				completed = true,
				success = false,
			)
			LogManager.severe("[AutoBone] Failed adjustment!", e)
		} finally {
			autoBoneThread = null
		}
	}

	private fun printTrackerInfo(trackers: FastList<TrackerFrames>) {
		val trackerInfo = StringBuilder()
		for (tracker in trackers) {
			val frame = tracker?.tryGetFrame(0) ?: continue

			// Add a comma if this is not the first item listed
			if (trackerInfo.isNotEmpty()) {
				trackerInfo.append(", ")
			}

			trackerInfo.append(frame.tryGetTrackerPosition()?.designation ?: "unassigned")

			// Represent the data flags
			val trackerFlags = StringBuilder()
			if (frame.hasData(TrackerFrameData.ROTATION)) {
				trackerFlags.append("R")
			}
			if (frame.hasData(TrackerFrameData.POSITION)) {
				trackerFlags.append("P")
			}
			if (frame.hasData(TrackerFrameData.ACCELERATION)) {
				trackerFlags.append("A")
			}
			if (frame.hasData(TrackerFrameData.RAW_ROTATION)) {
				trackerFlags.append("r")
			}

			// If there are data flags, print them in brackets after the designation
			if (trackerFlags.isNotEmpty()) {
				trackerInfo.append(" (").append(trackerFlags).append(")")
			}
		}
		LogManager.info("[AutoBone] (${trackers.size} trackers) [$trackerInfo]")
	}

	private fun printSkeletonRatios(skeleton: SkeletonConfigManager) {
		val neckLength = skeleton.getOffset(SkeletonConfigOffsets.NECK)
		val upperChestLength = skeleton.getOffset(SkeletonConfigOffsets.UPPER_CHEST)
		val chestLength = skeleton.getOffset(SkeletonConfigOffsets.CHEST)
		val waistLength = skeleton.getOffset(SkeletonConfigOffsets.WAIST)
		val hipLength = skeleton.getOffset(SkeletonConfigOffsets.HIP)
		val torsoLength = upperChestLength + chestLength + waistLength + hipLength
		val hipWidth = skeleton.getOffset(SkeletonConfigOffsets.HIPS_WIDTH)
		val legLength = skeleton.getOffset(SkeletonConfigOffsets.UPPER_LEG) +
			skeleton.getOffset(SkeletonConfigOffsets.LOWER_LEG)
		val lowerLegLength = skeleton.getOffset(SkeletonConfigOffsets.LOWER_LEG)

		val neckTorso = neckLength / torsoLength
		val chestTorso = (upperChestLength + chestLength) / torsoLength
		val torsoWaist = hipWidth / torsoLength
		val legTorso = legLength / torsoLength
		val legBody = legLength / (torsoLength + neckLength)
		val kneeLeg = lowerLegLength / legLength

		LogManager.info(
			"[AutoBone] Ratios: [{Neck-Torso: ${
				StringUtils.prettyNumber(neckTorso)}}, {Chest-Torso: ${
				StringUtils.prettyNumber(chestTorso)}}, {Torso-Waist: ${
				StringUtils.prettyNumber(torsoWaist)}}, {Leg-Torso: ${
				StringUtils.prettyNumber(legTorso)}}, {Leg-Body: ${
				StringUtils.prettyNumber(legBody)}}, {Knee-Leg: ${
				StringUtils.prettyNumber(kneeLeg)}}]",
		)
	}

	fun applyValues() {
		autoBone.applyAndSaveConfig()
	}
}
