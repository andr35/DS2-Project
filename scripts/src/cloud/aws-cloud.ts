import {EC2} from 'aws-sdk';
import {Instance, RunInstancesRequest} from 'aws-sdk/clients/ec2';
import {Cloud} from './cloud';
import {Options} from '../common/options';
import {CloudKeys} from '../common/cloud-keys';
import {ProjectUtils} from "../common/utils";
import * as aws from 'aws-sdk';
import * as fs from 'fs';
import * as path from 'path';
import * as chalk from 'chalk';
import * as scp2 from 'scp2';
import * as ssh2 from 'ssh2';

interface EC2Machine {
  id: number;
  instanceId: string;
  region: string;
  tracker?: boolean
  ip?: string;
}

// TODO check if it is possible to remove most of " new asw.EC2()"

export class AwsCloud implements Cloud {

  private images: { [region: string]: string } = { // TODO check if matching is correct
    'ap-south-1': 'ami-49e59a26', // Mumbai
    'eu-west-2': 'ami-cc7066a8', // London
    'eu-west-1': 'ami-6d48500b', // Ireland
    'ap-northeast-2': 'ami-94d20dfa', // Seoul?
    'ap-northeast-1': 'ami-785c491f', // Tokyo
    'sa-east-1': 'ami-34afc458', // Sao Paulo
    'ca-central-1': 'ami-b3d965d7', // Canada
    'ap-southeast-1': 'ami-2378f540', // Singapore
    'ap-southeast-2': 'ami-e94e5e8a', // Sidney
    'eu-central-1': 'ami-1c45e273', // Frankfurt
    'us-east-1': 'ami-d15a75c7', // North Virginia
    'us-east-2': 'ami-8b92b4ee', // Ohio
    'us-west-1': 'ami-73f7da13', // North California
    'us-west-2': 'ami-835b4efa' // Oregon
  };

  private defaultInstanceRequest: RunInstancesRequest = {
    ImageId: 'ami-d15a75c7', // ami-d7b9a2b1 amazon // ami-6d48500b ubuntu // ami-d15a75c7 ubuntu
    InstanceType: 't2.micro',
    MinCount: 1, // Number of instances
    MaxCount: 1
  };

  private trackerIp: string;

  private regions = [];
  private nodes: EC2Machine[] = [];
  private deployedNodes: EC2Machine[] = [];
  private jarPath = '';

  constructor(private options: Options) {
    // Load access key and secret key
    const keys = this.readKeys();
    aws.config.update({
      accessKeyId: keys.accessKey,
      secretAccessKey: keys.secretKey
    });
    // aws.config.loadFromPath(`${__dirname}/../aws.config.json`);
  }

  async startExperiment() {
    await this.fetchRegions();

    try {
      // Build project
      const jarDir = await this.buildProject();
      this.jarPath = jarDir + '/' + ProjectUtils.JAR_NAME;

      // Set region
      this.changeRegion(this.getRandomRegion());
      // Create EC2
      let ec2 = new aws.EC2();

      // Create tracker
      const trackerInstance = await this.createInstance(ec2, 0, true);
      this.nodes.push({
        id: 0,
        instanceId: trackerInstance.InstanceId,
        tracker: true,
        region: this.getCurrentRegion()
      });

      // Create nodes
      for (let i = 1; i <= this.options.nodes; i++) {
        // Set region  and create EC2
        this.changeRegion(this.getRandomRegion());
        ec2 = new aws.EC2(); // TODO

        const nodeInstance = await this.createInstance(ec2, 0, true);
        this.nodes.push({
          id: i,
          instanceId: nodeInstance.InstanceId,
          region: this.getCurrentRegion()
        });
      }

      // Deploy project on machines and start it
      await this.deployProjectWhenEC2IsRunning();

      console.log(chalk.bold.green('> Experiment started successfully. Nodes:'), this.deployedNodes);
    } catch (e) {
      console.log(chalk.bold.red('> An error occurred while starting experiment'), e);
    }
  }

  async shutdown() {
    await this.fetchRegions();

    for (let region of this.regions) {
      try {
        this.changeRegion(region);
        const ec2 = new aws.EC2();
        const instances = await this.getInstances(ec2);
        const instanceIds = instances.map(inst => inst.InstanceId);
        if (instanceIds.length > 0) {
          ec2.terminateInstances({InstanceIds: instanceIds}, (err, data) => {
            if (err) {
              console.error(chalk.bold.red(`> Fail to terminate EC2 instances in region "${region}"`), err);
            } else {
              data.TerminatingInstances.forEach(termInst => console.log(chalk.green(`> EC2 instance "${termInst.InstanceId}" (region "${region}")  terminated`)));
            }
          });
        } else {
          console.log(chalk.blue(`> No running EC2 instances in region "${region}"`));
        }
      } catch (e) {
        console.log(chalk.bold.red('> An error occurred during shutdown'), e);
      }
    }
  }

  async downloadReport() {
    await this.fetchRegions();

    const trackerInstance = await this.getTrackerInstance();
    if (trackerInstance === null) {
      console.log(chalk.yellow('Unable to find the tracker instance.'));
      process.exit(-1);
    }
    // TODO download report + log
    throw new Error('Method not implemented.');
  }

  async watchTrackerLogs() {
    await this.fetchRegions();

    const trackerInstance = await this.getTrackerInstance();
    if (trackerInstance === null) {
      console.log(chalk.yellow('Unable to find the tracker instance.'));
      process.exit(-1);
    }
    // TODO show log recorded on file
    throw new Error('Method not implemented.');
  }

  // /////////////////////////////////////////////
  //  Utils
  // /////////////////////////////////////////////

  private fetchRegions(): Promise<null> {
    return new Promise((resolve, reject) => {
      this.changeRegion('us-east-1');
      const ec2 = new aws.EC2();
      ec2.describeRegions({}, (err, data) => {
        if (err) {
          console.error(chalk.red('> Fail to fetch EC2 regions'), err);
          return reject(err);
        } else {
          console.log(chalk.green('> Fetched following regions'), data.Regions.map(r => r.RegionName));
          this.regions = data.Regions.map(r => r.RegionName);
          return resolve(null);
        }
      });
    });
  }

  private getUbuntuImageCode(region: string): string {
    return this.images[region];
  }

  private createInstance(ec2: EC2, id: number, tracker?: boolean): Promise<Instance> {
    return new Promise((resolve, reject) => {

      const imageId = this.getUbuntuImageCode(this.getCurrentRegion());
      if (!imageId) {
        console.error(chalk.red(`> ImageId for region "${this.getCurrentRegion()}" is not known, cannot create instance`));
        return reject('ImageId not known');
      }
      console.log(chalk.blue(`> Creating EC2 Instance in "${ec2.config.region}" using imageId "${imageId}"`));
      ec2.runInstances({
        ...this.defaultInstanceRequest,
        ImageId: imageId,
        KeyName: 'ds2'
      }, (err, data) => {
        if (err) {
          return reject(err);
        }
        const instance = data.Instances[0];
        console.log(chalk.bold.blue(`> EC2 Instance "${instance.InstanceId}" with id "${id}" created in "${ec2.config.region}", Public IP "${instance.PublicIpAddress}"`));

        // Add tags to the instance
        ec2.createTags({
          Resources: [instance.InstanceId],
          Tags: [
            {Key: 'type', Value: tracker ? 'tracker' : 'node'},
            {Key: 'id', Value: tracker ? '0' : `${id}`}
          ]
        }, err => err ? reject(err) : resolve(instance));
      });
    });
  }

  private getInstances(ec2: EC2): Promise<Instance[]> {
    return new Promise((resolve, reject) => {
      ec2.describeInstances({}, (err, data) => {
        if (err) {
          return reject(err);
        } else {
          let instances = [];
          for (let r = 0; r < data.Reservations.length; r++) {
            const reservation = data.Reservations[r];
            for (let i = 0; i < reservation.Instances.length; i++) {
              instances = [...instances, ...reservation.Instances];
            }
          }
          resolve(instances);
        }
      });
    });
  }

  private async getTrackerInstance(): Promise<Instance> {
    for (let region of this.regions) {
      this.changeRegion(region);
      const ec2 = new aws.EC2();
      const instances = await this.getInstances(ec2);

      for (const instance of instances) {
        const types = instance.Tags.filter(t => t.Key === 'type').map(t => t.Value);
        for (const t of types) {
          if (t === 'tracker') {
            return instance;
          }
        }
      }
    }
    return null;
  }

  private copyProject(instance: Instance, jarPath: string): Promise<void> {
    console.log(chalk.blue(`> Copy project in instance "${instance.InstanceId}" (ip: ${instance.PublicIpAddress})`));
    return new Promise((resolve, reject) => {
      scp2.scp(jarPath, {
        host: instance.PublicIpAddress,
        username: 'ubuntu',
        privateKey: fs.readFileSync(path.resolve(this.options.sshKey)),
        passphrase: this.options.sshPassphrase ? this.options.sshPassphrase : undefined,
        path: '/home/ubuntu/'
      }, err => err ? reject(err) : resolve());
    });
  }

  private startProjectInInstance(instance: Instance, node: EC2Machine): Promise<void> {
    return new Promise((resolve, reject) => {
      // Execute jar with options, redirect outputs to file
      const conn = new ssh2.Client();
      conn.on('ready', () => {
        console.log(chalk.green(`> SSH connection with "${node.instanceId}" (${node.region}) established. Start project...`));
        const cmd = this.getStartProjectCmd(node.id, this.options, node.tracker);
        conn.exec(cmd, (err, stream) => {
          if (err) {
            return reject(err);
          }
          stream.on('close', code => {
            const chalkColor = code === 0 ? chalk.green : chalk.red;
            console.log(chalkColor(`> SSH connection with "${node.instanceId}" (${node.region}) closed with code ${code}`));
            conn.end();
            if (code === 0) {
              if (node.tracker) { // When tracker is running, "publish" its ip address
                this.trackerIp = instance.PublicIpAddress;
              }
              resolve();
            } else {
              reject('Fail to run node');
            }
          }).on('data', data => {
            console.log(chalk.blue(`> ${node.instanceId} STDOUT: ${data}`));
          }).stderr.on('data', data => {
            console.log(chalk.red(`> ${node.instanceId} STDERR: ${data}`));
          });
        });
      }).connect({
        host: instance.PublicIpAddress,
        port: 22,
        username: 'ubuntu',
        privateKey: fs.readFileSync(path.resolve(this.options.sshKey)),
        passphrase: this.options.sshPassphrase ? this.options.sshPassphrase : undefined
      });
    });
  }

  private deployProjectWhenEC2IsRunning(): Promise<null> {
    return new Promise((resolve, reject) => {
      this.setDeployTimeout(30000, resolve, reject);
    });
  }

  private async setDeployTimeout(timeout: number, resolve, reject) {
    timeout = timeout > 10000 ? timeout : 10000;
    console.log(chalk.yellow(`> Wait ${timeout / 1000} sec before deploy`));
    setTimeout(async () => {

      const promises = [];
      for (let node of this.nodes) {
        const running = await this.isMachineRunning(node);
        if (running) {
          const instance = await this.getInstance(node);
          const prom = this.copyProject(instance, this.jarPath)
            .then(() => {
              if (!node.tracker && !this.trackerIp) { // must wait for tracker bootstrap
                throw new Error('Tracker not yet bootstrapped');
              } else {
                return this.startProjectInInstance(instance, node)
                  .then(() => {
                    this.nodes = this.nodes.filter(n => n.instanceId != node.instanceId); // Remove node from list
                    this.deployedNodes.push(node);
                    console.log(chalk.bold.green(`> Instance "${node.instanceId}", in "${node.region}" deploy completed`));
                    return node.instanceId;
                  });
              }
            })
            .catch(err => {
              console.log(chalk.red(`> Fail to deploy project on instance "${node.instanceId}" region "${node.region}". Retry at next timeout. Cause: `, err));
            });
          promises.push(prom);
        }
      }

      Promise.all(promises)
        .then(() => {
          if (this.nodes.length === 0) {
            resolve();
          } else {
            // Try again
            this.setDeployTimeout(timeout - 10000, resolve, reject);
          }
        })
        .catch(() => {
          // Try again
          this.setDeployTimeout(timeout - 10000, resolve, reject);
        });
    }, timeout);
  }

  private isMachineRunning(node: EC2Machine): Promise<boolean> {
    return new Promise((resolve, reject) => {
      this.changeRegion(node.region);
      const ec2 = new aws.EC2();
      ec2.describeInstanceStatus({InstanceIds: [node.instanceId]}, (err, data) => {
        if (err) {
          return reject(err);
        }
        return resolve(data.InstanceStatuses[0].InstanceState.Name === 'running');
      });
    });
  }

  private getInstance(node: EC2Machine): Promise<Instance> {
    return new Promise((resolve, reject) => {
      this.changeRegion(node.region);
      const ec2 = new aws.EC2();
      ec2.describeInstances({InstanceIds: [node.instanceId]}, (err, data) => {
        if (err) {
          return reject(err);
        } else {
          for (let r = 0; r < data.Reservations.length; r++) {
            const reservation = data.Reservations[r];
            if (reservation.Instances.length > 0) {
              return resolve(reservation.Instances[0]);
            }
          }
        }
      });
    });
  }


  //noinspection JSMethodCanBeStatic
  private async buildProject() {
    return ProjectUtils.buildProject();
  }

  private readKeys(): CloudKeys {
    const p = path.resolve(this.options.keys);
    const ck: CloudKeys = JSON.parse(fs.readFileSync(p, 'utf8'));
    if (!ck.accessKey || !ck.secretKey) {
      console.log(chalk.bold.red('> Missing "accessKey" or "secretKey" in key file'));
      process.exit(-1);
    }
    return ck;
  }

  private getStartProjectCmd(id: number, options: Options, tracker?: boolean): string {
    if (tracker) {
      return (options.nodes ? `NODES=${options.nodes} ` : ``) +
        (options.duration ? `DURATION=${options.duration} ` : ``) +
        (options.experiments ? `EXPERIMENTS=${options.experiments} ` : ``) +
        (options.repetitions ? `REPETITIONS=${options.repetitions} ` : ``) +
        (options.initialSeed ? `INITIAL_SEED=${options.initialSeed} ` : ``) +
        (options.reportPath ? `REPORT_PATH=${options.reportPath} ` : ``) +
        `java -jar ${ProjectUtils.JAR_NAME} tracker > ${ProjectUtils.EC2_LOG_PATH}`;
    } else {
      return `ID=${id} PORT=${10000 + id} java -jar ${ProjectUtils.JAR_NAME} node ${this.trackerIp} 10000 > ${ProjectUtils.EC2_LOG_PATH}`;
    }
  }

  private getRandomRegion(): string {
    return this.regions[Math.floor(Math.random() * this.regions.length)];
  }

  //noinspection JSMethodCanBeStatic
  private getCurrentRegion(): string {
    return aws.config.region;
  }

  //noinspection JSMethodCanBeStatic
  private changeRegion(region: string) {
    aws.config.update({region: region});
  }
}