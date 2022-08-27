package dev.slimevr.autobone;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.jme3.math.FastMath;

import io.eiren.util.collections.FastList;
import io.eiren.util.logging.LogManager;


public class HyperparamTuning<T> {

	HashMap<T, Float> hyperparameters = new HashMap<T, Float>();

	public HyperparamTuning() {
	}

	public void addHyperparameter(T key, float value) {
		hyperparameters.put(key, limitValue(value));
	}

	public void addHyperparameters(HashMap<T, Float> parameters) {
		hyperparameters.putAll(parameters);
		limitValues();
	}

	private float limitValue(float value) {
		return FastMath.clamp(value, 0f, 1f);
	}

	private void limitValues() {
		for (Entry<T, Float> entry : hyperparameters.entrySet()) {
			entry.setValue(limitValue(entry.getValue()));
		}
	}

	private void step(HyperparamTuningStep step) {
		FastList<Thread> threadList = new FastList<Thread>();

		for (Entry<T, Float> entry : step.tunedHyperparameters.entrySet()) {
			Thread thread = new Thread(() -> {
				ConcurrentHashMap<T, Float> tunedHyperparametersThreadBuffer = new ConcurrentHashMap<T, Float>();
				tunedHyperparametersThreadBuffer.putAll(step.tunedHyperparametersBuffer);

				float startingValue = entry.getValue();
				float startingError = step.lastErrors.getOrDefault(entry.getKey(), -1f);

				// If there's no last error, calculate it
				if (startingError < 0f) {
					startingError = step.lossFunc.apply(tunedHyperparametersThreadBuffer);
				}

				float minError = startingError;
				float bestValue = -1f;

				for (int i = -1; i <= 1; i += 2) {
					float newValue = limitValue(startingValue + (step.tuningRate * i));
					tunedHyperparametersThreadBuffer.put(entry.getKey(), newValue);
					float newError = step.lossFunc.apply(tunedHyperparametersThreadBuffer);

					if (newError < minError) {
						minError = newError;
						bestValue = newValue;
					}
				}

				if (bestValue >= 0f) {
					entry.setValue(bestValue);
					LogManager
						.info(
							"[HyperparamTuning] Changing \""
								+ entry.getKey()
								+ "\" from "
								+ startingValue
								+ " to "
								+ bestValue
						);
				}

				step.lastErrors.put(entry.getKey(), minError);
			});
			thread.start();

			threadList.add(thread);
		}

		for (Thread thread : threadList) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	public ConcurrentHashMap<T, Float> tune(Function<ConcurrentHashMap<T, Float>, Float> lossFunc) {
		ConcurrentHashMap<T, Float> tunedHyperparameters = new ConcurrentHashMap<T, Float>();

		// Check if no work is needed and return fast
		if (hyperparameters.size() <= 0) {
			return tunedHyperparameters;
		}

		// Create a buffer to use while tuning
		ConcurrentHashMap<T, Float> tunedHyperparametersBuffer = new ConcurrentHashMap<T, Float>();

		// Fill the map to tune
        tunedHyperparameters.putAll(hyperparameters);
        tunedHyperparametersBuffer.putAll(hyperparameters);

		HyperparamTuningStep step = new HyperparamTuningStep(
			lossFunc,
			tunedHyperparameters,
			tunedHyperparametersBuffer
		);

		for (int i = 0; i < 20; i++) {
			step(step);

			// Reset the buffer
			step.tunedHyperparametersBuffer.putAll(step.tunedHyperparameters);

			// Drop the tuning rate
			step.tuningRate *= step.tuningRateMultiplier;
		}

		// Done tuning
		return tunedHyperparameters;
	}

	private class HyperparamTuningStep {

		public final Function<ConcurrentHashMap<T, Float>, Float> lossFunc;

		public final ConcurrentHashMap<T, Float> tunedHyperparameters;
		public final ConcurrentHashMap<T, Float> tunedHyperparametersBuffer;

		public float tuningRate = 1.0f;
		public float tuningRateMultiplier = 0.75f;

		public final ConcurrentHashMap<T, Float> lastErrors = new ConcurrentHashMap<T, Float>();

		public HyperparamTuningStep(
			Function<ConcurrentHashMap<T, Float>, Float> lossFunc,
			ConcurrentHashMap<T, Float> tunedHyperparameters,
			ConcurrentHashMap<T, Float> tunedHyperparametersBuffer
		) {
			this.lossFunc = lossFunc;
			this.tunedHyperparameters = tunedHyperparameters;
			this.tunedHyperparametersBuffer = tunedHyperparametersBuffer;
		}
	}
}
