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

export class AwsCloud implements Cloud {

  private images: { [region: string]: string } = { // TODO check if matching is correct
    'ap-south-1': 'ami-e94e5e8a', // Sidney?
    'eu-west-2': 'ami-cc7066a8', // London
    'eu-west-1': 'ami-6d48500b', // Ireland
    'ap-northeast-2': 'ami-785c491f', // Tokyo?
    'ap-northeast-1': 'ami-94d20dfa', // Seoul?
    'sa-east-1': 'ami-34afc458', // Sao Paulo
    'ca-central-1': 'ami-b3d965d7', // Canada
    'ap-southeast-1': 'ami-2378f540', // Singapore?
    'ap-southeast-2': 'ami-49e59a26', // Mumbai?
    'eu-central-1': 'ami-1c45e273', // Frankfurt
    'us-east-1': 'ami-d15a75c7', // North Virginia
    'us-east-2': 'ami-8b92b4ee', // Ohio
    'us-west-1': 'ami-73f7da13', // North California // maybe switch with 'us-west-2'
    'us-west-2': 'ami-835b4efa' // Oregon
  };

  private defaultInstanceRequest: RunInstancesRequest = {
    ImageId: 'ami-d15a75c7', // ami-d7b9a2b1 amazon // ami-6d48500b ubuntu // ami-d15a75c7 ubuntu
    InstanceType: 't2.micro',
    MinCount: 1, // Number of instances
    MaxCount: 1
  };

  private regions = [];
  private nodes: { id: number; instanceId: string; ip: string }[] = [];

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

      // Set region
      this.changeRegion(this.getRandomRegion());
      // Create EC2
      let ec2 = new aws.EC2();

      // Create tracker
      const trackerInstance = await this.createInstance(ec2, 0, true);
      // TODO issue: must wait until state is running + some seconds
      await this.copyProject(trackerInstance, jarDir + '/' + ProjectUtils.JAR_NAME);
      await this.startProjectInInstance(trackerInstance);
      this.nodes.push({id: 0, instanceId: trackerInstance.InstanceId, ip: trackerInstance.PublicIpAddress});

      // Create nodes
      for (let i = 1; i <= this.options.nodes; i++) {
        // Set region  and create EC2
        this.changeRegion(this.getRandomRegion());
        ec2 = new aws.EC2();

        const nodeInstance = await this.createInstance(ec2, 0, true);
        // TODO issue: must wait until state is running + some seconds
        await this.copyProject(nodeInstance, jarDir + '/' + ProjectUtils.JAR_NAME);
        await this.startProjectInInstance(nodeInstance);
        this.nodes.push({id: i, instanceId: nodeInstance.InstanceId, ip: nodeInstance.PublicIpAddress});
      }

      console.log(chalk.bold.green('> Experiment started successfully. Nodes:'), this.nodes);
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
              data.TerminatingInstances.forEach(termInst => console.log(chalk.green(`EC2 instance "${termInst.InstanceId}" terminated.`)));
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
        KeyName: 'awsds2'
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
    console.log(chalk.blue(`> Copy project in instance "${instance.InstanceId}"`));
    return new Promise((resolve, reject) => {
      scp2.scp(jarPath, {
        host: instance.PublicIpAddress,
        username: 'ubuntu',
        privateKey: fs.readFileSync(path.resolve(this.options.sshKey)),
        path: '/home/ubuntu/'
      }, err => err ? reject(err) : resolve());
    });
  }

  private startProjectInInstance(instance: Instance): Promise<void> {

    return new Promise((resolve, reject) => {
      // TODO start project
      // execute jar with options, redirect outputs to file
      resolve();
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

  private getRandomRegion(): string {
    return 'us-east-1'; // TODO edit
    // return this.regions[Math.floor(Math.random() * this.regions.length)];
  }

  private getCurrentRegion(): string {
    return aws.config.region;
  }

  //noinspection JSMethodCanBeStatic
  private changeRegion(region: string) {
    aws.config.update({region: region});
  }
}