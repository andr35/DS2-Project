import {DropletResponse} from "../ocean-interface";
const DigitalOcean = require('do-wrapper');
import {Cloud} from './cloud';
import * as path from "path";
import * as fs from "fs";
import * as chalk from "chalk";

export class OceanCloud implements Cloud {

  private api;
  private token: string;

  constructor(private options: any) {
    this.token = this.readToken();
    this.api = new DigitalOcean(this.token);
  }

  startExperiment(): void {
    throw new Error('Method not implemented.');
  }

  async shutdown() {
    try {
      const res: DropletResponse = await this.api.dropletsGetAll({});
      if (res.droplets.length > 0) {
        for (let droplet of res.droplets) {
          try {
            await this.api.dropletsDelete(droplet.id);
            console.log(chalk.green(`> Machine "${droplet.id}" from "${droplet.region.name}" stopped`));
          } catch (e) {
            console.log(chalk.bold.red(`> Fail to stop machine "${droplet.id}" from "${droplet.region.name}"`), e);
          }
        }
      }
    } catch (e) {
      console.log(chalk.bold.red('> An error occurred while stopping machines'), e);
    }
  }

  downloadReport(): void {
    throw new Error('Method not implemented.');
  }

  watchTrackerLogs(): void {
    throw new Error('Method not implemented.');
  }

  // //////////////////////////////////////////
  //  Utils
  // //////////////////////////////////////////


  private readToken(): string {
    const p = path.resolve(this.options.keys);
    const ck = JSON.parse(fs.readFileSync(p, 'utf8'));
    if (!ck.token) {
      console.log(chalk.bold.red('> Missing "token" in key file'));
      process.exit(-1);
    }
    return ck.token;
  }
}