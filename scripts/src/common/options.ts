export interface Options {
  nodes?: number;
  duration?: number;
  experiments?: number;
  repetitions?: number;
  initialSeed?: number;
  reportPath?: string;

  keys?: string;
  sshKey?: string;
  downloadDir?: string;
}