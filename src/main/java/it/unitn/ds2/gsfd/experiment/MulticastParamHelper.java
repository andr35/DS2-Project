package it.unitn.ds2.gsfd.experiment;

public class MulticastParamHelper {

	/**
	 * Helper that computes expected time of first multicast for a values
	 * and finds multicastParam (a) that scores the closest time.
	 *
	 * @param n Number of nodes of the system.
	 * @param maxWait Maximum number of times multicast can be postponed.
	 * @param expectedFirstMulticast Desired time of first multicast.
	 *
	 * @return Value a associated to the most accurate expected first multicast.
	 */
	private static double findMulticastParam(int n, long maxWait, long expectedFirstMulticast) {
		double aFirst = 1.0;
		double aLast = 30.0; // maximum a to test, to guarantee termination
		double aStep = 0.25;

		double a = aFirst;

		double aClosest = 0.0;
		double diff = 0.0;

		// compute e, expected time of first multicast, wrt to a values
		while (a <= aLast) {

			double e = 0.0;

			for (double t = 0.0; t <= maxWait; t++) {
				double m1 = t/maxWait;
				double e1 = t * ((1 - Math.pow(1 - Math.pow(m1, a), n)));

				double e2 = 1.0;
				for (double w = 0.0; w <= t - 1; w++) {
					double m2 = w/maxWait;
					e2 = e2 * (1 - (1 - Math.pow(1 - Math.pow(m2, a), n)));
				}

				e  += e1 * e2;
			}

			// as long as we don't surpass required time, a is the last one tried
			if (e <= expectedFirstMulticast) {
				aClosest = a;
				diff = Math.abs(expectedFirstMulticast - e);
			} else { // when we surpass, take the closer
				if (diff > Math.abs(expectedFirstMulticast - e)) {
					// last e is closer
					return a;
				} else { // previous e was closer
					return aClosest;
				}
			}

			// next value of a
			a += aStep;
		}

		return aClosest;
	}
}
