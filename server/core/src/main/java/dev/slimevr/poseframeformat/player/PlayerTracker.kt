package dev.slimevr.poseframeformat.player

import dev.slimevr.poseframeformat.trackerdata.TrackerFrames
import dev.slimevr.tracking.trackers.Tracker
import io.github.axisangles.ktmath.Quaternion
import kotlin.math.abs

class PlayerTracker(
	val trackerFrames: TrackerFrames,
	val tracker: Tracker,
	private var internalCursor: Int = 0,
	private var internalScale: Float = 1f,
	private var internalMounting: Float = 0f,
	private var internalW: Float = 1f,
	private var internalX: Float = 0f,
	private var internalY: Float = 0f,
	private var internalZ: Float = 0f,
) {

	var cursor: Int
		get() = internalCursor
		set(value) {
			val limitedCursor = limitCursor(value)
			internalCursor = limitedCursor
			setTrackerStateFromIndex(limitedCursor)
		}

	var scale: Float
		get() = internalScale
		set(value) {
			internalScale = value
			setTrackerStateFromIndex()
		}

	var mounting: Float
		get() = internalMounting
		set(value) {
			internalMounting = value
			setTrackerStateFromIndex()
		}

	var w: Float
		get() = internalW
		set(value) {
			internalW = value
			setTrackerStateFromIndex()
		}

	var x: Float
		get() = internalX
		set(value) {
			internalX = value
			setTrackerStateFromIndex()
		}

	var y: Float
		get() = internalY
		set(value) {
			internalY = value
			setTrackerStateFromIndex()
		}

	var z: Float
		get() = internalZ
		set(value) {
			internalZ = value
			setTrackerStateFromIndex()
		}

	init {
		setTrackerStateFromIndex(limitCursor())
	}

	fun limitCursor(cursor: Int): Int {
		return if (cursor < 0 || trackerFrames.frames.isEmpty()) {
			return 0
		} else if (cursor >= trackerFrames.frames.size) {
			return trackerFrames.frames.size - 1
		} else {
			cursor
		}
	}

	fun limitCursor(): Int {
		val limitedCursor = limitCursor(internalCursor)
		internalCursor = limitedCursor
		return limitedCursor
	}

	private fun setTrackerStateFromIndex(index: Int = internalCursor) {
		val frame = trackerFrames.tryGetFrame(index) ?: return

		/*
		 * TODO: No way to set adjusted rotation manually? That might be nice to have...
		 * for now we'll stick with just setting the final rotation as raw and not
		 * enabling any adjustments
		 */

		val trackerPosition = frame.tryGetTrackerPosition()
		if (trackerPosition != null) {
			tracker.trackerPosition = trackerPosition
		}

		val rotation = frame.tryGetRotation()
		if (rotation != null) {
			if (internalMounting != 0f || w != 1f || x != 0f || y != 0f || z != 0f) {
				val mountOffset = Quaternion(1f - abs(internalMounting), 0f, internalMounting, 0f).unit()
				val attachmentFix = if (w != 0f || x != 0f || y != 0f || z != 0f) {
					Quaternion(internalW, internalX, internalY, internalZ).unit()
				} else {
					Quaternion.IDENTITY
				}
				tracker.setRotation(mountOffset.inv() * (rotation * attachmentFix * mountOffset))
			} else {
				tracker.setRotation(rotation)
			}
		}

		val position = frame.tryGetPosition()
		if (position != null) {
			tracker.position = position * internalScale
		}

		val acceleration = frame.tryGetAcceleration()
		if (acceleration != null) {
			tracker.setAcceleration(acceleration * internalScale)
		}
	}
}
