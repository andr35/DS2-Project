export interface Cloud {

  startExperiment();
  shutdown();
  downloadReport();
  watchTrackerLogs();
  runningMachines();
}