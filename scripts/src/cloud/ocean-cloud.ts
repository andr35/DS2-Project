import {Cloud} from './cloud';

export class OceanCloud implements Cloud {

  constructor(private options: any) {
  }

  startExperiment(): void {
    throw new Error('Method not implemented.');
  }

  shutdown(): void {
    throw new Error('Method not implemented.');
  }

  downloadReport(): void {
    throw new Error('Method not implemented.');
  }

  watchTrackerLogs(): void {
    throw new Error('Method not implemented.');
  }
}