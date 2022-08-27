package dev.slimevr.autobone;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;

import com.jme3.math.Vector3f;

import dev.slimevr.VRServer;
import dev.slimevr.autobone.AutoBone.AutoBoneResults;
import dev.slimevr.autobone.errors.AutoBoneException;
import dev.slimevr.poserecorder.PoseFrameIO;
import dev.slimevr.poserecorder.PoseFrameSkeleton;
import dev.slimevr.poserecorder.PoseFrameTracker;
import dev.slimevr.poserecorder.PoseFrames;
import dev.slimevr.poserecorder.PoseRecorder;
import dev.slimevr.poserecorder.TrackerFrame;
import dev.slimevr.poserecorder.TrackerFrameData;
import dev.slimevr.posestreamer.BVHFileStream;
import dev.slimevr.posestreamer.PoseStreamer;
import dev.slimevr.vr.processor.skeleton.SkeletonConfig;
import dev.slimevr.vr.processor.skeleton.SkeletonConfigOffsets;
import dev.slimevr.vr.processor.skeleton.SkeletonConfigValues;
import dev.slimevr.vr.trackers.TrackerPosition;
import io.eiren.util.StringUtils;
import io.eiren.util.collections.FastList;
import io.eiren.util.logging.LogManager;


public class AutoBoneHandler {

	private final VRServer server;
	private final PoseRecorder poseRecorder;
	private final AutoBone autoBone;

	private ReentrantLock recordingLock = new ReentrantLock();
	private Thread recordingThread = null;

	private ReentrantLock saveRecordingLock = new ReentrantLock();
	private Thread saveRecordingThread = null;

	private ReentrantLock autoBoneLock = new ReentrantLock();
	private Thread autoBoneThread = null;

	private List<AutoBoneListener> listeners = new CopyOnWriteArrayList<>();

	public AutoBoneHandler(VRServer server) {
		this.server = server;
		this.poseRecorder = new PoseRecorder(server);
		this.autoBone = new AutoBone(server);
	}

	public void addListener(AutoBoneListener listener) {
		this.listeners.add(listener);
	}

	public void removeListener(AutoBoneListener listener) {
		this.listeners.removeIf(l -> listener == l);
	}

	private void announceProcessStatus(
		AutoBoneProcessType processType,
		String message,
		long current,
		long total,
		boolean completed,
		boolean success
	) {
		listeners.forEach(listener -> {
			listener
				.onAutoBoneProcessStatus(processType, message, current, total, completed, success);
		});
	}

	private void announceProcessStatus(
		AutoBoneProcessType processType,
		String message,
		boolean completed,
		boolean success
	) {
		announceProcessStatus(processType, message, 0, 0, completed, success);
	}

	private void announceProcessStatus(AutoBoneProcessType processType, String message) {
		announceProcessStatus(processType, message, false, true);
	}

	private void announceProcessStatus(AutoBoneProcessType processType, long current, long total) {
		announceProcessStatus(processType, null, current, total, false, true);
	}

	public String getLengthsString() {
		return autoBone.getLengthsString();
	}

	private AutoBoneResults processFrames(
		PoseFrames frames,
		ConcurrentHashMap<SkeletonConfigValues, Float> skeletonConfigValues
	) throws AutoBoneException {
		return autoBone
			.processFrames(
				frames,
				autoBone.getConfig().calcInitError,
				autoBone.getConfig().targetHeight,
				(epoch) -> {
					listeners.forEach(listener -> {
						listener.onAutoBoneEpoch(epoch);
					});
				},
				skeletonConfigValues
			);
	}

	public boolean startProcessByType(AutoBoneProcessType processType) {
		switch (processType) {
			case RECORD:
				startRecording();
				break;

			case SAVE:
				saveRecording();
				break;

			case PROCESS:
				processRecording();
				break;

			case APPLY:
				applyValues();
				break;

			default:
				return false;
		}

		return true;
	}

	public void startRecording() {
		recordingLock.lock();

		try {
			// Prevent running multiple times
			if (recordingThread != null) {
				return;
			}

			Thread thread = new Thread(this::startRecordingThread);
			recordingThread = thread;
			thread.start();
		} finally {
			recordingLock.unlock();
		}
	}

	private void startRecordingThread() {
		try {
			if (poseRecorder.isReadyToRecord()) {
				announceProcessStatus(AutoBoneProcessType.RECORD, "Recording...");

				// 1000 samples at 20 ms per sample is 20 seconds
				int sampleCount = this.autoBone.getConfig().sampleCount;
				long sampleRate = this.autoBone.getConfig().sampleRateMs;
				Future<PoseFrames> framesFuture = poseRecorder
					.startFrameRecording(sampleCount, sampleRate, progress -> {
						announceProcessStatus(
							AutoBoneProcessType.RECORD,
							progress.frame,
							progress.totalFrames
						);
					});
				PoseFrames frames = framesFuture.get();
				LogManager.info("[AutoBone] Done recording!");

				// Save a recurring recording for users to send as debug info
				announceProcessStatus(AutoBoneProcessType.RECORD, "Saving recording...");
				autoBone.saveRecording(frames, "LastABRecording.pfr");

				if (this.autoBone.getConfig().saveRecordings) {
					announceProcessStatus(
						AutoBoneProcessType.RECORD,
						"Saving recording (from config option)..."
					);
					autoBone.saveRecording(frames);
				}

				listeners.forEach(listener -> {
					listener.onAutoBoneRecordingEnd(frames);
				});

				announceProcessStatus(AutoBoneProcessType.RECORD, "Done recording!", true, true);
			} else {
				announceProcessStatus(
					AutoBoneProcessType.RECORD,
					"The server is not ready to record",
					true,
					false
				);
				LogManager.severe("[AutoBone] Unable to record...");
				return;
			}
		} catch (Exception e) {
			announceProcessStatus(
				AutoBoneProcessType.RECORD,
				String.format("Recording failed: %s", e.getMessage()),
				true,
				false
			);
			LogManager.severe("[AutoBone] Failed recording!", e);
		} finally {
			recordingThread = null;
		}
	}

	public void saveRecording() {
		saveRecordingLock.lock();

		try {
			// Prevent running multiple times
			if (saveRecordingThread != null) {
				return;
			}

			Thread thread = new Thread(this::saveRecordingThread);
			saveRecordingThread = thread;
			thread.start();
		} finally {
			saveRecordingLock.unlock();
		}
	}

	private void saveRecordingThread() {
		try {
			Future<PoseFrames> framesFuture = poseRecorder.getFramesAsync();
			if (framesFuture != null) {
				announceProcessStatus(AutoBoneProcessType.SAVE, "Waiting for recording...");
				PoseFrames frames = framesFuture.get();

				if (frames.getTrackerCount() <= 0) {
					throw new IllegalStateException("Recording has no trackers");
				}

				if (frames.getMaxFrameCount() <= 0) {
					throw new IllegalStateException("Recording has no frames");
				}

				announceProcessStatus(AutoBoneProcessType.SAVE, "Saving recording...");
				autoBone.saveRecording(frames);

				announceProcessStatus(AutoBoneProcessType.SAVE, "Recording saved!", true, true);
			} else {
				announceProcessStatus(AutoBoneProcessType.SAVE, "No recording found", true, false);
				LogManager.severe("[AutoBone] Unable to save, no recording was done...");
				return;
			}
		} catch (Exception e) {
			announceProcessStatus(
				AutoBoneProcessType.SAVE,
				String.format("Failed to save recording: %s", e.getMessage()),
				true,
				false
			);
			LogManager.severe("[AutoBone] Failed to save recording!", e);
		} finally {
			saveRecordingThread = null;
		}
	}

	public void processRecording() {
		autoBoneLock.lock();

		try {
			// Prevent running multiple times
			if (autoBoneThread != null) {
				return;
			}

			Thread thread = new Thread(this::processRecordingThread);
			autoBoneThread = thread;
			thread.start();
		} finally {
			autoBoneLock.unlock();
		}
	}

	private void processRecordingThread() {
		try {
			announceProcessStatus(AutoBoneProcessType.PROCESS, "Loading recordings...");
			List<Pair<String, PoseFrames>> frameRecordings = autoBone.loadRecordings();

			if (!frameRecordings.isEmpty()) {
				LogManager.info("[AutoBone] Done loading frames!");
			} else {
				Future<PoseFrames> framesFuture = poseRecorder.getFramesAsync();
				if (framesFuture != null) {
					announceProcessStatus(AutoBoneProcessType.PROCESS, "Waiting for recording...");
					PoseFrames frames = framesFuture.get();

					if (frames.getTrackerCount() <= 0) {
						throw new IllegalStateException("Recording has no trackers");
					}

					if (frames.getMaxFrameCount() <= 0) {
						throw new IllegalStateException("Recording has no frames");
					}

					frameRecordings.add(Pair.of("<Recording>", frames));
				} else {
					announceProcessStatus(
						AutoBoneProcessType.PROCESS,
						"No recordings found...",
						true,
						false
					);
					LogManager
						.severe(
							"[AutoBone] No recordings found in \""
								+ AutoBone.getLoadDir().getPath()
								+ "\" and no recording was done..."
						);
					return;
				}
			}

			announceProcessStatus(AutoBoneProcessType.PROCESS, "Processing recording(s)...");
			LogManager.info("[AutoBone] Processing frames...");
			FastList<Float> heightPercentError = new FastList<Float>(frameRecordings.size());
			SkeletonConfig skeletonConfigBuffer = new SkeletonConfig(false);
			for (Pair<String, PoseFrames> recording : frameRecordings) {
				LogManager
					.info("[AutoBone] Processing frames from \"" + recording.getKey() + "\"...");

				List<PoseFrameTracker> trackers = recording.getValue().getTrackers();

				File autoboneRecordingDir = new File("AutoBone Recordings");
				autoboneRecordingDir.mkdirs();

				// Remove the first number of frames
				if (false) {
					int framesToTrim = 41;
					boolean saveTrimmed = true;

					for (PoseFrameTracker tracker : trackers) {
						if (tracker == null)
							continue;

						for (int i = 0; i < framesToTrim; i++) {
							tracker.removeFrame(0);
						}
					}

					if (saveTrimmed) {
						PoseFrameIO
							.writeToFile(
								new File(autoboneRecordingDir, recording.getKey() + "_trimmed.pfr"),
								recording.getValue()
							);
					}
				}

				// Isolate specific frames
				if (false) {
					int[] framesToKeep = new int[] {
						166,
						370,
					};
					boolean saveIsolated = true;

					for (PoseFrameTracker tracker : trackers) {
						if (tracker == null)
							continue;

						for (int i = tracker.getFrameCount() - 1; i >= 0; i--) {
							if (ArrayUtils.contains(framesToKeep, i))
								continue;

							tracker.removeFrame(i);
						}
					}

					if (saveIsolated) {
						PoseFrameIO
							.writeToFile(
								new File(
									autoboneRecordingDir,
									recording.getKey() + "_isolated.pfr"
								),
								recording.getValue()
							);
					}
				}

				// Offset the recording position
				if (true) {
					Vector3f offset = new Vector3f(2.6f, -0.20f, -0.6f);
					boolean saveOffset = true;

					for (PoseFrameTracker tracker : trackers) {
						if (tracker == null)
							continue;

						for (int i = 0; i < tracker.getFrameCount(); i++) {
							TrackerFrame frame = tracker.safeGetFrame(i);
							if (frame != null && frame.hasPosition()) {
								frame.position.addLocal(offset);
							}
						}
					}

					if (saveOffset) {
						PoseFrameIO
							.writeToFile(
								new File(autoboneRecordingDir, recording.getKey() + "_offset.pfr"),
								recording.getValue()
							);
					}
				}

				// Remove specific tracker positions
				if (false) {
					TrackerPosition[] positions = new TrackerPosition[] {
						TrackerPosition.HIP
					};
					boolean saveWithoutTrackers = true;

					for (int i = trackers.size() - 1; i >= 0; i--) {
						PoseFrameTracker tracker = trackers.get(i);
						if (tracker == null)
							continue;

						if (ArrayUtils.contains(positions, tracker.getBodyPosition())) {
							trackers.remove(i);
							tracker.clearFrames();
						}
					}

					if (saveWithoutTrackers) {
						PoseFrameIO
							.writeToFile(
								new File(
									autoboneRecordingDir,
									recording.getKey() + "_without_trackers.pfr"
								),
								recording.getValue()
							);
					}
				}

				StringBuilder trackerInfo = new StringBuilder();
				for (PoseFrameTracker tracker : trackers) {
					if (tracker == null)
						continue;

					TrackerFrame frame = tracker.safeGetFrame(0);
					if (frame == null || !frame.hasData(TrackerFrameData.DESIGNATION))
						continue;

					if (trackerInfo.length() > 0) {
						trackerInfo.append(", ");
					}

					trackerInfo.append(frame.designation.designation);

					if (frame.hasData(TrackerFrameData.POSITION)) {
						trackerInfo.append(" (P)");
					}
				}

				LogManager
					.info(
						"[AutoBone] ("
							+ trackers.size()
							+ " trackers) ["
							+ trackerInfo.toString()
							+ "]"
					);

				HyperparamTuning<SkeletonConfigValues> tuning = new HyperparamTuning<SkeletonConfigValues>();
				for (SkeletonConfigValues configVal : SkeletonConfigValues.values) {
					tuning.addHyperparameter(configVal, configVal.defaultValue);
				}

				ConcurrentHashMap<SkeletonConfigValues, Float> skeletonConfigValues = tuning.tune(params -> {
					try {
						AutoBone autoBoneThread = new AutoBone(server);
						AutoBoneResults autoBoneResults = autoBoneThread
							.processFrames(
								recording.getValue(),
								autoBoneThread.getConfig().calcInitError,
								autoBoneThread.getConfig().targetHeight,
								(epoch) -> {
									listeners.forEach(listener -> {
										listener.onAutoBoneEpoch(epoch);
									});
								},
								params
							);
						LogManager.info("[AutoBone] Final error: " + autoBoneResults.epochError);
						return autoBoneResults.epochError;
					} catch (AutoBoneException e) {
						e.printStackTrace();
					}
					return 100000f;
				});

				for (Entry<SkeletonConfigValues, Float> entry : skeletonConfigValues.entrySet()) {
					LogManager
						.info("Config \"" + entry.getKey() + "\" end value: " + entry.getValue());
				}

				AutoBoneResults autoBoneResults = processFrames(
					recording.getValue(),
					skeletonConfigValues
				);
				LogManager.info("[AutoBone] Last final error: " + autoBoneResults.epochError);
				heightPercentError.add(autoBoneResults.getHeightOffset());
				LogManager.info("[AutoBone] Done processing!");

				// Generate BVH for AutoBone recording
				if (true) {
					PoseFrameSkeleton skeleton = new PoseFrameSkeleton(trackers, null);
					skeleton
						.getSkeletonConfig()
						.setConfigs(autoBoneResults.configValues, null, null);
					PoseStreamer streamer = new PoseStreamer(skeleton);

					File bvhFolder = new File("BVH Recordings");
					bvhFolder.mkdirs();
					try (
						BVHFileStream bvhFileStream = new BVHFileStream(
							new File(bvhFolder, recording.getKey() + ".bvh")
						)
					) {
						long sampleRate = autoBone.getConfig().sampleRateMs;
						streamer.setOutput(bvhFileStream, sampleRate);

						for (int i = 0; i < recording.getValue().getMaxFrameCount(); i++) {
							skeleton.setCursor(i);
							skeleton.updatePose();
							streamer.captureFrame();
						}

						streamer.closeOutput();
					}
				}

				// #region Stats/Values
				skeletonConfigBuffer.setConfigs(autoBoneResults.configValues, null, null);

				float neckLength = skeletonConfigBuffer.getOffset(SkeletonConfigOffsets.NECK);
				float chestDistance = skeletonConfigBuffer
					.getOffset(SkeletonConfigOffsets.CHEST);
				float torsoLength = skeletonConfigBuffer
					.getOffset(SkeletonConfigOffsets.TORSO);
				float hipWidth = skeletonConfigBuffer
					.getOffset(SkeletonConfigOffsets.HIPS_WIDTH);
				float legsLength = skeletonConfigBuffer
					.getOffset(SkeletonConfigOffsets.LEGS_LENGTH);
				float kneeHeight = skeletonConfigBuffer
					.getOffset(SkeletonConfigOffsets.KNEE_HEIGHT);

				float neckTorso = neckLength / torsoLength;
				float chestTorso = chestDistance / torsoLength;
				float torsoWaist = hipWidth / torsoLength;
				float legTorso = legsLength / torsoLength;
				float legBody = legsLength / (torsoLength + neckLength);
				float kneeLeg = kneeHeight / legsLength;

				LogManager
					.info(
						"[AutoBone] Ratios: [{Neck-Torso: "
							+ StringUtils.prettyNumber(neckTorso)
							+ "}, {Chest-Torso: "
							+ StringUtils.prettyNumber(chestTorso)
							+ "}, {Torso-Waist: "
							+ StringUtils.prettyNumber(torsoWaist)
							+ "}, {Leg-Torso: "
							+ StringUtils.prettyNumber(legTorso)
							+ "}, {Leg-Body: "
							+ StringUtils.prettyNumber(legBody)
							+ "}, {Knee-Leg: "
							+ StringUtils.prettyNumber(kneeLeg)
							+ "}]"
					);
				LogManager.info("[AutoBone] Length values: " + autoBone.getLengthsString());
			}

			if (!heightPercentError.isEmpty()) {
				float mean = 0f;
				for (float val : heightPercentError) {
					mean += val;
				}
				mean /= heightPercentError.size();

				float std = 0f;
				for (float val : heightPercentError) {
					float stdVal = val - mean;
					std += stdVal * stdVal;
				}
				std = (float) Math.sqrt(std / heightPercentError.size());

				LogManager
					.info(
						"[AutoBone] Average height error: "
							+ StringUtils.prettyNumber(mean, 6)
							+ " (SD "
							+ StringUtils.prettyNumber(std, 6)
							+ ")"
					);
			}
			// #endregion

			listeners.forEach(listener -> {
				listener.onAutoBoneEnd(autoBone.legacyConfigs);
			});

			announceProcessStatus(AutoBoneProcessType.PROCESS, "Done processing!", true, true);
		} catch (Exception e) {
			announceProcessStatus(
				AutoBoneProcessType.PROCESS,
				String.format("Processing failed: %s", e.getMessage()),
				true,
				false
			);
			LogManager.severe("[AutoBone] Failed adjustment!", e);
		} finally {
			autoBoneThread = null;
		}
	}

	public void applyValues() {
		autoBone.applyAndSaveConfig();
		announceProcessStatus(AutoBoneProcessType.APPLY, "Adjusted values applied!", true, true);
		// TODO Update GUI values after applying? Is that needed here?
	}

}
