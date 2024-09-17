package dev.slimevr.autobone

import io.eiren.util.logging.LogManager
import kotlin.math.*

class LBFGS {
	/**
	 * Function to be minimized.
	 * This example uses the Rosenbrock function, a common test problem for optimization algorithms.
	 */
	fun f(x: DoubleArray): Double {
		var sum = 0.0
		for (i in 0 until x.size - 1) {
			val p1 = x[i + 1] - (x[i] * x[i])
			val p2 = x[i] - 1.0
			sum += 100.0 * (p1 * p1) + (p2 * p2)
		}
		return sum
	}

	/**
	 * Compute the gradient of the function f at the point x using central finite differences.
	 *
	 * @param f Function to compute the gradient for.
	 * @param x Point at which to compute the gradient.
	 * @return The gradient of f at x.
	 */
	private fun grad(f: (DoubleArray) -> Double, x: DoubleArray): DoubleArray {
		// Small step size for finite differences
		val h = 1e-6
		val gradient = DoubleArray(x.size)

		// Loop through each component of x and compute partial derivatives
		for (i in x.indices) {
			val xFor = x.copyOf()
			val xBack = x.copyOf()
			xFor[i] += h
			xBack[i] -= h
			// Central difference approximation
			gradient[i] = (f(xFor) - f(xBack)) / (2.0 * h)
		}
		return gradient
	}

	/**
	 * Line search using backtracking and Wolfe conditions.
	 *
	 * @param f Objective function.
	 * @param x Current point.
	 * @param p Search direction.
	 * @param gradient Current gradient.
	 * @param lowerBounds The lower bounds of x.
	 * @param upperBounds The upper bounds of x.
	 * @return Step size (alpha) that satisfies the Wolfe conditions.
	 */
	private fun lineSearch(
		f: (DoubleArray) -> Double,
		x: DoubleArray,
		p: DoubleArray,
		gradient: DoubleArray,
		lowerBounds: DoubleArray?,
		upperBounds: DoubleArray?,
	): Double {
		var alpha = 1.0
		val c1 = 1e-4
		val c2 = 0.9
		val fx = f(x)
		val xNew = x.copyOf()
		var newGradient: DoubleArray

		// TODO Value varies on problem complexity, should probably fail if an outcome is not achieved
		for (i in 0 until 100) {
			// Update xNew with step alpha in direction p
			for (j in x.indices) {
				xNew[j] = x[j] + alpha * p[j]
				// Project within bounds
				if (lowerBounds != null && upperBounds != null) {
					xNew[j] = xNew[j].coerceIn(lowerBounds[j], upperBounds[j])
				}
			}
			newGradient = grad(f, xNew)
			alpha *= 0.9

			// Check Wolfe conditions
			if (f(xNew) < fx + (c1 * alpha * gradient.dot(p)) && newGradient.dot(p) > c2 * gradient.dot(p)) {
				break
			}
		}

		return alpha
	}

	/**
	 * Two-loop recursion to compute an approximation of the inverse Hessian-vector product.
	 *
	 * @param gradient Current gradient.
	 * @param sStored Stored differences in positions (x).
	 * @param yStored Stored differences in gradients.
	 * @param memory Number of stored corrections.
	 * @return The direction for the next step.
	 */
	private fun recursionTwoLoop(gradient: DoubleArray, sStored: List<DoubleArray>, yStored: List<DoubleArray>, memory: Int): DoubleArray {
		val q = gradient.copyOf()
		val alpha = DoubleArray(memory)
		val rou = DoubleArray(memory) { 1.0 / yStored[it].dot(sStored[it]) }

		// First loop: move through stored vectors to compute q
		for (i in (memory - 1) downTo 0) {
			alpha[i] = rou[i] * sStored[i].dot(q)
			for (j in q.indices) {
				q[j] -= alpha[i] * yStored[i][j]
			}
		}

		// Scaling factor for the initial Hessian approximation
		val yStoredLast = yStored.last()
		val hk0 = sStored.last().dot(yStoredLast) / yStoredLast.dot(yStoredLast)
		for (i in q.indices) {
			q[i] *= hk0 // Apply the scaling factor
		}

		// Second loop: apply stored vectors again to refine q
		for (i in 0 until memory) {
			val beta = rou[i] * yStored[i].dot(q)
			val ab = alpha[i] - beta
			for (j in q.indices) {
				q[j] += ab * sStored[i][j]
			}
		}

		return q
	}

	/**
	 * Limited-memory Broyden–Fletcher–Goldfarb–Shanno (L-BFGS) algorithm for unconstrained optimization.
	 *
	 * @param f Objective function.
	 * @param x0 Initial point.
	 * @param maxIterations Maximum number of iterations to perform.
	 * @param memory Memory parameter controlling how many past updates are stored.
	 * @param lowerBounds The lower bounds of x.
	 * @param upperBounds The upper bounds of x.
	 * @return Optimized point.
	 */
	fun lbfgs(
		f: (DoubleArray) -> Double,
		x0: DoubleArray,
		maxIterations: Int,
		memory: Int,
		lowerBounds: DoubleArray? = null,
		upperBounds: DoubleArray? = null,
	): DoubleArray {
		var gradient = grad(f, x0)
		val x = x0.copyOf()

		// Stores for past updates of x and gradients
		val sStored = mutableListOf<DoubleArray>()
		val yStored = mutableListOf<DoubleArray>()

		val tolerance = 1e-5

		for (i in 0 until maxIterations) {
			if (gradient.norm() <= tolerance) {
				break
			}

			val p = if (i > 0) {
				recursionTwoLoop(gradient, sStored, yStored, minOf(i, memory))
			} else {
				gradient.copyOf()
			}

			// Negate direction
			for (j in p.indices) p[j] = -p[j]

			// Perform line search with bounds handling
			val alpha = lineSearch(f, x, p, gradient, lowerBounds, upperBounds)

			// Update position and project to bounds if necessary
			val s = DoubleArray(p.size) { alpha * p[it] }
			sStored.add(s)
			if (sStored.size > memory) sStored.removeAt(0)

			val oldGradient = gradient.copyOf()

			for (j in x.indices) {
				x[j] += alpha * p[j]
				if (lowerBounds != null && upperBounds != null) {
					// Project within bounds
					x[j] = x[j].coerceIn(lowerBounds[j], upperBounds[j])
				}
			}

			gradient = grad(f, x)
			val y = DoubleArray(gradient.size) { gradient[it] - oldGradient[it] }
			yStored.add(y)
			if (yStored.size > memory) yStored.removeAt(0)
		}

		return x
	}

	/**
	 * Compute the Euclidean norm of a vector.
	 */
	private fun DoubleArray.norm(): Double = sqrt(this.sumOf { it * it })

	/**
	 * Compute the dot product between two vectors.
	 */
	private fun DoubleArray.dot(other: DoubleArray): Double = this.zip(other) { a, b -> a * b }.sum()

	// Test the L-BFGS implementation with optional bounds
	fun main() {
		val min = 1e-2
		val x0 = doubleArrayOf(0.5, 2.0, 0.5, 0.1)
		val lowerBounds = doubleArrayOf(min, 0.5, min, min)
		val upperBounds = doubleArrayOf(2.0, 3.0, 2.5, 4.0)

		val result = lbfgs(::f, x0, 100, 10, lowerBounds, upperBounds)

		LogManager.info("Optimal solution with bounds: ${result.contentToString()}")
	}
}
