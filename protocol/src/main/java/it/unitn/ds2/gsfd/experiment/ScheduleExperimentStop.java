package it.unitn.ds2.gsfd.experiment;

import java.io.Serializable;

/**
 * Message used from the Tracker to stop the running experiment.
 */
public final class ScheduleExperimentStop implements Serializable {

	private final int experiment;

	public ScheduleExperimentStop(int experiment) {
		this.experiment = experiment;
	}

	public int getExperiment() {
		return experiment;
	}
}
