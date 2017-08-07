package it.unitn.ds2.gsfd.messages;

import java.io.Serializable;

/**
 * Message used to stop the running experiment.
 * When a node receives this message, it should stop
 * the protocol and reset its state.
 */
public final class StopExperiment implements Serializable {
}
