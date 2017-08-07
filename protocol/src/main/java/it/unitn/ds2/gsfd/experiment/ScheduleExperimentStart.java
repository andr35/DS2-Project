package it.unitn.ds2.gsfd.experiment;

import java.io.Serializable;

/**
 * Message used from the Tracker to start the running experiment.
 */
public final class ScheduleExperimentStart implements Serializable {

	private final int experiment;

	public ScheduleExperimentStart(int experiment) {
		this.experiment = experiment;
	}

	public int getExperiment() {
		return experiment;
	}
}
