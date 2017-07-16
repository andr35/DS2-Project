export interface Options {
  nodes?: number;
  duration?: number;
  experiments?: number;
  repetitions?: number;
  initialSeed?: number;
  reportPath?: string;
  timeBetweenExperiments?: number;
  minFailureRounds?: number;
  maxFailureRounds?: number;

  local?: boolean;
  keys?: string;
  sshKey?: string;
  sshPassphrase?: string;
  downloadDir?: string;
  reportDir?: string;
}