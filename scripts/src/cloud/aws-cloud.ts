import * as aws from "aws-sdk";
import {EC2} from "aws-sdk";
import {Instance, RunInstancesRequest} from "aws-sdk/clients/ec2";
import {Cloud} from "./cloud";
import {Options} from "../common/options";
import {CloudKeys} from "../common/cloud-keys";
import {ProjectUtils} from "../common/utils";
import {spawn} from "child_process";
import * as fs from "fs";
import * as path from "path";
import * as chalk from "chalk";
import * as scp2 from "scp2";
import * as ssh2 from "ssh2";

interface EC2Machine {
  instance: Instance;
  id: number;
  tracker?: boolean
  region: string;
  projectCopied?: boolean;
  projectCopiedPending?: boolean;
  projectStarted?: boolean;
  projectStartedPending?: boolean;
  installJavaComplete?: boolean;
  installJavaPending?: boolean;
}

export class AwsCloud implements Cloud {

  private static SSH_KEY_NAME = 'ds2';
  private static EC2_INSTANCE_USERNAME = 'ubuntu';
  private static EC2_INSTANCE_BASE_PATH = '/home/ubuntu/';

  private static ubuntuImages: { [region: string]: string } = {
    'ap-south-1': 'ami-49e59a26', // Mumbai
    'eu-west-2': 'ami-cc7066a8', // London
    'eu-west-1': 'ami-6d48500b', // Ireland
    'ap-northeast-2': 'ami-94d20dfa', // Seoul
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

  private static defaultInstanceRequest: RunInstancesRequest = {
    ImageId: 'ami-d15a75c7',
    InstanceType: 't2.micro',
    MinCount: 1, // Number of instances
    MaxCount: 1
  };

  // Set when tracker ec2 machine is running
  private trackerIp: string = null;

  private regions: string[] = [];
  private ec2: { [region: string]: EC2 } = {};

  // Filled with created machines
  private nodes: { [instanceId: string]: EC2Machine } = {};
  // Filled with completely project deployed and running nodes
  private deployedNodes: EC2Machine[] = [];

  constructor(private experimentName: string, private options: Options) {
    // Load access key and secret key
    const keys = this.readKeys();
    aws.config.update({
      accessKeyId: keys.accessKey,
      secretAccessKey: keys.secretKey
    });
  }

  /**
   * Init EC2 API.
   * @return {Promise<null>} Done
   */
  async init(): Promise<void> {
    console.log(chalk.blue('> Fetching AWS regions...'));
    try {
      this.regions = await AwsCloud.fetchRegions();
      for (let region of this.regions) {
        // Initialize reusable ec2 for each region
        AwsCloud.changeRegion(region);
        this.ec2[region] = new aws.EC2();
      }
      return null;
    } catch (err) {
      console.log(chalk.red('> Error occurred'), err);
      process.exit((-1));
    }
  }


  /**
   * Start experiment.
   * @return {Promise<void>}
   */
  async startExperiment() {
    try {
      // Build project
      await ProjectUtils.buildProject();

      // Fetch AWS regions
      await this.init();

      // Create tracker
      let reg = this.getRandomRegion();
      const trackerInstance = await this.createEC2Instance(0, reg, true);
      this.nodes[trackerInstance.InstanceId] = {
        instance: trackerInstance,
        region: reg,
        tracker: true,
        id: 0
      };

      // Create nodes
      for (let i = 1; i <= this.options.nodes; i++) {
        reg = this.getRandomRegion();
        try {
          const trackerInstance = await this.createEC2Instance(i, reg);
          this.nodes[trackerInstance.InstanceId] = {
            instance: trackerInstance,
            region: reg,
            id: i
          };
        } catch (err) {
          if (err && err.code === 'InstanceLimitExceeded') {
            console.log(chalk.yellow('i Limit for EC2 machine in the region exceeded, change region...'));
            // Retry instantiation
            this.regions.splice(this.regions.indexOf(reg), 1);
            i--;
          }
        }
      }

      // Deploy project on machines and start it
      await new Promise((resolve) => {
        this.startDeployTimeout(30000, resolve);
      });
      console.log(chalk.bold.green(`> Experiment "${this.experimentName}" started successfully. Nodes:`), this.deployedNodes.map(n => {
        //noinspection PointlessBooleanExpressionJS
        return {
          id: n.id,
          tracker: !!n.tracker,
          instanceId: n.instance.InstanceId,
          ip: n.instance.PublicIpAddress,
          region: n.region
        }
      }));
      process.exit(0);
    } catch (e) {
      console.log(chalk.bold.red('> An error occurred while starting experiment'), e);
    }
  }

  /**
   * Shutdown all EC2 instances.
   * @return {Promise<void>}
   */
  async shutdown() {
    await this.init();

    if (!this.experimentName) {
      console.log(chalk.yellow('> Warning: This command will terminate ALL the running EC2 machines!'));
    }

    for (let region of this.regions) {
      try {
        const instances = await this.getInstancesForRegion(region);
        let instancesToShutdown = [];

        // Filter by experiment name
        if (this.experimentName) {
          for (const instance of instances) {
            if (AwsCloud.belongToExperiment(instance, this.experimentName)) {
              instancesToShutdown.push(instance);
            }
          }
        } else {
          instancesToShutdown = instances;
        }

        const instanceIds = instancesToShutdown.map(inst => inst.InstanceId);
        if (instanceIds.length > 0) {
          this.ec2[region].terminateInstances({InstanceIds: instanceIds}, (err, data) => {
            if (err) {
              console.error(chalk.bold.red(`> Fail to terminate EC2 instances in region "${region}"`), err);
            } else {
              data.TerminatingInstances.forEach(termInst => console.log(chalk.green(`> EC2 instance ${AwsCloud.ec2InstanceString(termInst.InstanceId, region)} terminated`)));
            }
          });
        } else {
          console.log(chalk.blue(`> No running EC2 instances in region "${region}"`));
        }
      } catch (err) {
        console.log(chalk.bold.red('> An error occurred during shutdown'), err);
      }
    }
  }

  async downloadReport() {
    await this.init();

    console.log(chalk.blue(`> Searching EC2 machines...`));
    let trackerInstance = null;
    const nodeInstances = [];
    for (let region of this.regions) {
      const instances = await this.getInstancesForRegion(region);
      for (let instance of instances) {
        if (AwsCloud.belongToExperiment(instance, this.experimentName)) {
          if (AwsCloud.isTracker(instance)) {
            trackerInstance = instance;
          } else {
            nodeInstances.push(instance);
          }
        }
      }
    }

    try {
      if (trackerInstance === null) {
        console.log(chalk.red('> Unable to discover the tracker instance.'));
        process.exit(-1);
      } else {
        console.log(chalk.yellow('> Trying to download report...'));
        await this.downloadDir(trackerInstance.PublicIpAddress, this.options.reportDir, this.options.downloadDir);
        console.log(chalk.yellow('> Trying to download tracker log...'));
        await this.downloadSingleFile(trackerInstance.PublicIpAddress, ProjectUtils.EC2_LOG_PATH, this.options.downloadDir, 'tracker.log');

        for (let instance of nodeInstances) {
          const nodeId = AwsCloud.getInstanceNodeId(instance);
          console.log(chalk.yellow(`> Trying to download node "${nodeId}" log...`));
          await this.downloadSingleFile(instance.PublicIpAddress, ProjectUtils.EC2_LOG_PATH, this.options.downloadDir, `node-${nodeId}.log`);
        }

        console.log(chalk.bold.green(`> Complete! Files downloaded in ${this.options.downloadDir}`));
      }
    } catch (err) {
      console.log(chalk.bold.red(`> Error occurred`), err);
    }
  }

  async watchTrackerLogs() {
    await this.init();

    try {
      console.log(chalk.blue(`> Search tracker...`));
      const trackerInstance = await this.getTrackerInstance();
      if (trackerInstance === null) {
        console.log(chalk.red('Unable to discover the tracker instance.'));
        process.exit(-1);
      } else {
        console.log(chalk.yellow(`> Trying to connect with "${trackerInstance.InstanceId}" (${trackerInstance.PublicIpAddress})...`));
        const sshConnection = new ssh2.Client();
        sshConnection.on('ready', async () => {
          console.log(chalk.green(`> SSH connection with "${trackerInstance.InstanceId}" (${trackerInstance.PublicIpAddress}) established.`));
          sshConnection.exec('cat ' + ProjectUtils.EC2_LOG_PATH, (err, stream) => {
            if (err) {
              throw err;
            } else {
              stream.on('close', () => {
                process.exit(0);
              }).on('data', data => {
                console.log(chalk.blue(`> STDOUT: ${data}`));
              }).stderr.on('data', data => {
                console.log(chalk.red(`> STDERR: ${data}`));
              });
            }
          });
        }).connect({
          host: trackerInstance.PublicIpAddress,
          port: 22,
          username: AwsCloud.EC2_INSTANCE_USERNAME,
          privateKey: fs.readFileSync(path.resolve(this.options.sshKey)),
          passphrase: this.options.sshPassphrase ? this.options.sshPassphrase : undefined
        });
      }
    } catch (err) {
      console.log(chalk.bold.red(`Error occurred`), err);
    }
  }

  async runningMachines() {
    await this.init();

    console.log(chalk.blue(`> Searching EC2 machines...`));
    for (let region of this.regions) {
      const instances = await this.getInstancesForRegion(region);
      console.log(chalk.bold.blue(`> Machines deployed in [${region}]:`));
      for (let instance of instances) {
        console.log(chalk.bold.white(`> Instance id ${instance.InstanceId}`));
        console.log(chalk.white(`    Ip Address     : ${instance.PublicIpAddress}`));
        console.log(chalk.white(`    Node Id        : ${AwsCloud.getInstanceNodeId(instance)}`));
        console.log(chalk.white(`    Is Tracker     : ${AwsCloud.isTracker(instance)}`));
        console.log(chalk.white(`    Experiment Name: ${AwsCloud.getInstanceExperiment(instance)}`));
      }
    }
  }


  // /////////////////////////////////////////////
  //  Utils
  // /////////////////////////////////////////////

  /**
   * Fetch EC2 available regions
   * @return {Promise<string[]>} List of regions
   */
  private static async fetchRegions(): Promise<string[]> {
    try {
      AwsCloud.changeRegion('us-east-1'); // Use default region
      const ec2 = new aws.EC2();
      const data = await ec2.describeRegions({}).promise();
      console.log(chalk.green('> Fetched following regions'), data.Regions.map(r => r.RegionName).join(', '));
      return (data.Regions.map(r => r.RegionName));
    } catch (err) {
      console.error(chalk.red('> Fail to fetch EC2 regions'), err);
      throw err;
    }
  }

  /**
   * Fetch all running EC2 instances in a region.
   * @param region Region.
   * @return {Promise<Array>} Instances.
   */
  private async getInstancesForRegion(region: string): Promise<Instance[]> {
    const data = await this.ec2[region].describeInstances({}).promise();
    let instances = [];
    for (let r = 0; r < data.Reservations.length; r++) {
      const reservation = data.Reservations[r];
      for (let i = 0; i < reservation.Instances.length; i++) {
        instances = [...instances, ...reservation.Instances];
      }
    }
    return instances;
  }

  /**
   * Create and run a new EC2 instance.
   * @param id Node id.
   * @param region Region.
   * @param tracker Is tracker or not.
   * @return {Promise<Instance>} Created instance.
   */
  private async createEC2Instance(id: number, region: string, tracker?: boolean): Promise<Instance> {
    // Get proper image id
    const imageId = AwsCloud.getUbuntuImageCode(region);
    if (!imageId) {
      console.error(chalk.red(`> ImageId for region "${region}" is not known, cannot create instance`));
      throw new Error('ImageId not known');
    }

    // Create instance
    console.log(chalk.blue(`> Creating EC2 Instance in "${region}" using imageId "${imageId}"...`));
    const data = await this.ec2[region].runInstances({
      ...AwsCloud.defaultInstanceRequest,
      ImageId: imageId,
      KeyName: AwsCloud.SSH_KEY_NAME
    }).promise();

    const instance = data.Instances[0];
    console.log(chalk.bold.green(`> EC2 Instance ${AwsCloud.ec2InstanceString(instance.InstanceId, region, id, instance.PublicIpAddress)} created`));

    // Add tags to the instance
    await this.ec2[region].createTags({
      Resources: [instance.InstanceId],
      Tags: [
        {Key: 'type', Value: tracker ? 'tracker' : 'node'},
        {Key: 'id', Value: tracker ? '0' : `${id}`},
        {Key: 'experiment', Value: this.experimentName}
      ]
    }).promise();
    return instance;
  }

  /**
   * Search the tracker EC2 instance in the world.
   *
   * @return {Promise<Instance>} Tracker instance.
   */
  private async getTrackerInstance(): Promise<Instance> {
    for (let region of this.regions) {
      const instances = await this.getInstancesForRegion(region);
      for (const instance of instances) {
        // Search for tracker
        if (AwsCloud.isTracker(instance) && AwsCloud.belongToExperiment(instance, this.experimentName)) {
          return instance;
        }
      }
    }
    return null;
  }

  /**
   * Check if an EC2 machine is a tracker.
   * @param instance Instance.
   * @return {boolean} True if machine is a tracker, false otherwise.
   */
  private static isTracker(instance: Instance): boolean {
    const types = instance.Tags.filter(t => t.Key === 'type').map(t => t.Value);
    return types.length > 0 && types[0] === 'tracker';
  }

  /**
   * Check if a EC2 machine belongs to a certain experiment.
   * @param instance Instance.
   * @param experimentName Name of the experiment.
   * @return {boolean} True if machine belong to the experiment, false otherwise.
   */
  private static belongToExperiment(instance: Instance, experimentName: string): boolean {
    const experiments = instance.Tags.filter(t => t.Key === 'experiment').map(e => e.Value);
    return experiments.length > 0 && experiments[0] === experimentName;
  }

  /**
   * Get the node id assigned to an EC2 machine.
   * @param instance Instance.
   * @return {string} Node Id.
   */
  private static getInstanceNodeId(instance: Instance): string {
    const ids = instance.Tags.filter(t => t.Key === 'id').map(e => e.Value);
    return ids.length > 0 ? ids[0] : '';
  }

  /**
   * Get the name of the experiment to which is assigned the EC2 machine.
   * @param instance Instance.
   * @return {string} Experiment name.
   */
  private static getInstanceExperiment(instance: Instance): string {
    const exps = instance.Tags.filter(t => t.Key === 'experiment').map(e => e.Value);
    return exps.length > 0 ? exps[0] : '';
  }

  /**
   * Copy project in EC2 instance.
   * @param node Node.
   * @return {Promise<void>}
   */
  private async copyProject(node: EC2Machine): Promise<void> {
    if (node.projectCopied) {
      return;
    } else if (node.projectCopiedPending) {
      throw AwsCloud.ec2NodeString(node) + ' Copying project files...';
    } else {
      this.nodes[node.instance.InstanceId].projectCopiedPending = true;
      console.log(chalk.blue(`> Coping project to node ${AwsCloud.ec2NodeString(node)}...`));
      try {
        await new Promise((resolve, reject) => {
          scp2.scp(ProjectUtils.BUILD_PATH + '/' + ProjectUtils.JAR_NAME, {
            host: node.instance.PublicIpAddress,
            username: AwsCloud.EC2_INSTANCE_USERNAME,
            privateKey: fs.readFileSync(path.resolve(AwsCloud.resolve(this.options.sshKey))),
            passphrase: this.options.sshPassphrase ? this.options.sshPassphrase : undefined,
            path: AwsCloud.EC2_INSTANCE_BASE_PATH
          }, err => err ? reject(err) : resolve());
        });
        // Update node status
        this.nodes[node.instance.InstanceId].projectCopied = true;
        this.nodes[node.instance.InstanceId].projectCopiedPending = false;
        console.log(chalk.green(`> Project copied in node ${AwsCloud.ec2NodeString(node)}`));
        return;
      } catch (err) {
        this.nodes[node.instance.InstanceId].projectCopiedPending = false;
        console.log(chalk.red(`> Fail to copy project in node ${AwsCloud.ec2InstanceString(node.instance.InstanceId, node.region, node.id, node.instance.PublicIpAddress)}`));
        throw err;
      }
    }
  }

  /**
   * Install Java and Start the experiment on one machine.
   *
   * @param node Node
   * @return {Promise<void>}
   */
  private async startProjectInInstance(node: EC2Machine): Promise<void> {
    if (node.projectStarted) {
      return;
    } else if (node.projectStartedPending) {
      throw AwsCloud.ec2NodeString(node) + ' Project start is pending';
    } else {
      this.nodes[node.instance.InstanceId].projectStartedPending = true;
      // Start project
      await new Promise((resolve, reject) => {
        const sshConnection = new ssh2.Client();
        sshConnection.on('ready', async () => {
          console.log(chalk.green(`> SSH connection with "${AwsCloud.ec2NodeString(node)}" established. Start project...`));
          // Start project
          console.log(chalk.blue(`> Run project on machine ${AwsCloud.ec2NodeString(node)}...`));

          const cmd = this.getStartProjectCmd(node.id, this.options, node.instance.PublicIpAddress, node.tracker);
          sshConnection.exec(cmd, (err, stream) => {
            if (err) {
              reject(err);
            } else {
              stream.on('close', code => {
                const chalkColor = code === 0 ? chalk.green : chalk.red;
                console.log(chalkColor(`> Start project on ${AwsCloud.ec2NodeString(node)} closed with code ${code}`));
                sshConnection.end();

                if (code === 0) {
                  // Everything ok
                  if (node.tracker) { // When tracker is running, "publish" its ip address
                    this.trackerIp = node.instance.PublicIpAddress;
                  }
                  // Update status
                  this.nodes[node.instance.InstanceId].projectStartedPending = false;
                  this.nodes[node.instance.InstanceId].projectStarted = true;
                  resolve();
                } else {
                  this.nodes[node.instance.InstanceId].projectStartedPending = false;
                  reject(err);
                }
                return (code === 0) ? resolve() : reject('Fail to start project');
              }).on('data', data => {
                console.log(chalk.blue(`> ${AwsCloud.ec2NodeString(node)} STDOUT: ${data}`));
              }).stderr.on('data', data => {
                console.log(chalk.red(`> ${AwsCloud.ec2NodeString(node)} STDERR: ${data}`));
              });
            }
          });
        }).connect({
          host: node.instance.PublicIpAddress,
          port: 22,
          username: AwsCloud.EC2_INSTANCE_USERNAME,
          privateKey: fs.readFileSync(path.resolve(this.options.sshKey)),
          passphrase: this.options.sshPassphrase ? this.options.sshPassphrase : undefined
        });
      });
    }
  }

  /**
   * Start a timeout after which the script will try to deploy the project on the machines.
   *
   * @param timeout Time
   * @param resolve Resolve
   * @return {Promise<void>}
   */
  private startDeployTimeout(timeout: number, resolve: () => void): void {
    // Limit timeout
    // timeout = timeout > 10000 ? timeout : 10000;
    timeout = timeout < 120000 ? timeout : 120000;
    console.log(chalk.yellow(`> Wait ${timeout / 1000} sec before try deploy...`));

    // Set timeout
    setTimeout(async () => {
      console.log(chalk.yellow(`> Try to deploy...`));

      try {
        for (let instanceId in this.nodes) {
          // Check if node is running
          const running = await this.isMachineRunning(this.nodes[instanceId]);
          if (running) {
            // Update instance info (get ip address)
            this.nodes[instanceId].instance = await this.getInstance(this.nodes[instanceId]);
            // try to deploy
            this.tryDeploy(this.nodes[instanceId])
              .then(machine => {
                if (isObjectEmpty(this.nodes)) { // Ok, all nodes deployed
                  return resolve();
                } else { // Some nodes still need to be deployed
                  if (machine.tracker) {
                    // Trigger immediately a new timeout
                    console.log(chalk.bold.green('> Tracker bootstrapped! Triggering a new deploy timeout...'));
                    this.startDeployTimeout(300, resolve);
                  }
                }
              })
              .catch(err => {
                console.log(chalk.magenta('> Timeout report:'), err);
              });
          }
        }
      } catch (err) {
        console.log(chalk.bgRed('> A machine fail the deploy.'), err);
      }

      console.log(chalk.bold.blue('> Setting next timeout in ' + ((timeout + 20000) / 1000) + ' sec...'));
      // Set next timeout
      this.startDeployTimeout(timeout + 20000, resolve);
    }, timeout);
  }

  /**
   * Try to perform the deploy on a machine.
   *
   * @param node Node
   * @return {Promise<null>}
   */
  private async tryDeploy(node: EC2Machine): Promise<EC2Machine> {
    // Copy project
    await this.copyProject(node);
    // Install Java
    await this.installJava(node);

    // Check if tracker bootstrapped
    if (!node.tracker && !this.trackerIp) { // Must wait for tracker bootstrap
      throw AwsCloud.ec2NodeString(node) + ' Tracker not yet bootstrapped';
    } else {
      // Try to start project
      await this.startProjectInInstance(node);

      // Success
      this.deployedNodes.push(node);
      delete this.nodes[node.instance.InstanceId];
      console.log(chalk.bold.green(`> Instance ${AwsCloud.ec2NodeString(node)} deploy completed`));
      return node;
    }
  }

  /**
   * Check whether a machine is in a running status or not.
   * @param node Node to check.
   * @return {Promise<boolean>} True if is running.
   */
  private async isMachineRunning(node: EC2Machine): Promise<boolean> {
    const data = await this.ec2[node.region].describeInstanceStatus({InstanceIds: [node.instance.InstanceId]}).promise();
    const instance = data.InstanceStatuses[0];
    return instance && instance.InstanceState.Name === 'running';
  }

  /**
   * Get machine instance details.
   * @param node Node
   * @return {Promise<Instance>} Instance.
   */
  private async getInstance(node: EC2Machine): Promise<Instance> {
    const data = await this.ec2[node.region].describeInstances({InstanceIds: [node.instance.InstanceId]}).promise();
    for (let r = 0; r < data.Reservations.length; r++) {
      const reservation = data.Reservations[r];
      if (reservation.Instances.length > 0) {
        return reservation.Instances[0];
      }
    }
  }

  /**
   * Install Java on a machine.
   *
   * @param node Node.
   * @return {Promise<void>}
   */
  private async installJava(node: EC2Machine): Promise<void> {
    if (node.installJavaComplete) {
      return;
    } else if (node.installJavaPending) {
      throw AwsCloud.ec2NodeString(node) + ' Java installation pending...';
    } else {
      try {
        this.nodes[node.instance.InstanceId].installJavaPending = true;
        console.log(chalk.blue(`> Try to install Java on machine ${AwsCloud.ec2NodeString(node)}...`));
        await new Promise((resolve, reject) => {
          const sshConnection = new ssh2.Client();
          sshConnection.on('ready', async () => {
            console.log(chalk.green(`> SSH connection with "${AwsCloud.ec2NodeString(node)}" established. Installing Java...`));
            sshConnection.exec('sudo apt-get update && sudo apt-get -y install openjdk-8-jre', (err, stream) => {
              if (err) {
                reject(err);
              } else {
                stream.on('close', code => {
                  const chalkColor = code === 0 ? chalk.green : chalk.red;
                  console.log(chalkColor(`> Machine ${AwsCloud.ec2NodeString(node)}. Java install result code ${code}`));
                  this.nodes[node.instance.InstanceId].installJavaComplete = true;
                  this.nodes[node.instance.InstanceId].installJavaPending = false;
                  sshConnection.end();
                  return (code === 0) ? resolve() : reject('Java installation failed');
                }).on('data', () => {
                  // console.log(chalk.blue(`> ${AwsCloud.ec2NodeString(node)} STDOUT: ${data}`));
                }).stderr.on('data', () => {
                  // console.log(chalk.red(`> ${AwsCloud.ec2NodeString(node)} STDERR: ${data}`));
                });
              }
            });
          }).connect({
            host: node.instance.PublicIpAddress,
            port: 22,
            username: AwsCloud.EC2_INSTANCE_USERNAME,
            privateKey: fs.readFileSync(path.resolve(this.options.sshKey)),
            passphrase: this.options.sshPassphrase ? this.options.sshPassphrase : undefined
          });
        });
      } catch (err) {
        this.nodes[node.instance.InstanceId].installJavaPending = false;
        console.log(chalk.red(`> Machine ${AwsCloud.ec2NodeString(node)}. Fail while install Java `), err);
      }
    }
  }

  private async downloadSingleFile(ip: string, src: string, dest: string, destFileName: string) {
    await new Promise((resolve, reject) => {
      scp2.scp({
        host: ip,
        username: AwsCloud.EC2_INSTANCE_USERNAME,
        privateKey: fs.readFileSync(path.resolve(this.options.sshKey)),
        passphrase: this.options.sshPassphrase ? this.options.sshPassphrase : undefined,
        path: src
      }, path.resolve(dest) + '/' + destFileName, err => err ? reject(err) : resolve());
    });
  }

  private async downloadDir(ip: string, src: string, dest: string) {
    await new Promise((resolve, reject) => {
      const scpCmd = spawn('scp', ['-i', path.resolve(this.options.sshKey), '-r', AwsCloud.EC2_INSTANCE_USERNAME + '@' + ip + ':' + src, path.resolve(dest)]);

      scpCmd.stdout.on('data', (data: string) => {
        if (data.indexOf('passpharse') !== -1) {
          scpCmd.stdin.write(this.options.sshPassphrase);
        } else if (data.indexOf('The authenticity of host') !== -1) {
          scpCmd.stdin.write('yes');
        }
        console.log(`stdout: ${data}`);
      });

      scpCmd.stderr.on('data', (data) => {
        console.log(`stderr: ${data}`);
      });

      scpCmd.on('close', (code) => {
        return code === 0 ? resolve() : reject('Scp error ' + code);
      });
    });
  }

  /**
   * Get keys from provided json.
   * @return {CloudKeys} Aws Keys.
   */
  private readKeys(): CloudKeys {
    const p = AwsCloud.resolve(this.options.keys);
    const ck: CloudKeys = JSON.parse(fs.readFileSync(p, 'utf8'));
    if (!ck.accessKey || !ck.secretKey) {
      console.log(chalk.bold.red('> Missing "accessKey" or "secretKey" in key file'));
      process.exit(-1);
    }
    return ck;
  }

  /**
   * Create the command to run an Akka tracker or node.
   * @param id Id of Akka node
   * @param options Tracker experiments options
   * @param myIp Ip of the machine where the command will be run
   * @param tracker true if the node will be a tracker
   * @return {string} cmd
   */
  private getStartProjectCmd(id: number, options: Options, myIp: string, tracker?: boolean): string {
    if (tracker) {
      return `HOST=${myIp} PORT=10000 ` +
        (options.nodes !== undefined ? `NODES=${options.nodes} ` : ``) +
        (options.duration !== undefined ? `DURATION=${options.duration} ` : ``) +
        (options.experiments !== undefined ? `EXPERIMENTS=${options.experiments} ` : ``) +
        (options.repetitions !== undefined ? `REPETITIONS=${options.repetitions} ` : ``) +
        (options.initialSeed !== undefined ? `INITIAL_SEED=${options.initialSeed} ` : ``) +
        (options.reportPath !== undefined ? `REPORT_PATH=${options.reportPath} ` : ``) +
        (options.minFailureRounds !== undefined ? `MIN_FAILURE_ROUNDS=${options.minFailureRounds} ` : ``) +
        (options.maxFailureRounds !== undefined ? `MAX_FAILURE_ROUNDS=${options.maxFailureRounds} ` : ``) +
        (options.timeBetweenExperiments !== undefined ? `TIME_BETWEEN_EXPERIMENTS=${options.timeBetweenExperiments} ` : ``) +
        `java -jar ${ProjectUtils.JAR_NAME} tracker > ${ProjectUtils.EC2_LOG_PATH} &`;
    } else {
      return `HOST=${myIp} PORT=${10000 + id} ID=${id} java -jar ${ProjectUtils.JAR_NAME} node ${this.trackerIp} 10000 > ${ProjectUtils.EC2_LOG_PATH} &`;
    }
  }

  /**
   * Get a random region from the available ones.
   * @return {string} Region.
   */
  private getRandomRegion(): string {
    return this.regions[Math.floor(Math.random() * this.regions.length)];
  }

  /**
   * Change AWS SDK region.
   * @param region Region.
   */
  private static changeRegion(region: string) {
    aws.config.update({region: region});
  }

  /**
   * Get ubuntu image code depending on region.
   * @param region Region.
   * @return {string} Ubuntu image code.
   */
  private static getUbuntuImageCode(region: string): string {
    return AwsCloud.ubuntuImages[region];
  }

  /**
   * Pretty print Instance info.
   * @param instanceId Instance id
   * @param region Region
   * @param id "Akka" id
   * @param ip Ip
   * @return {string} Print
   */
  private static ec2InstanceString(instanceId: string, region: string, id?: number, ip?: string): string {
    return chalk.underline(`["${instanceId}" - ${region}` + (id ? ` - ${id}` : ``) + (ip ? ` - ${ip}` : ``) + `]`);
  }

  /**
   * Pretty print Instance info.
   *
   * @param node Node
   * @return {string} Print
   */
  private static ec2NodeString(node: EC2Machine): string {
    return AwsCloud.ec2InstanceString(node.instance.InstanceId, node.region, node.id, node.instance.PublicIpAddress);
  }

  private static resolve(name: String): string {
    if (name[0] === '~') {
      return path.join(process.env.HOME, name.slice(1));
    } else {
      return path.resolve(name);
    }
  }
}

function isObjectEmpty(obj: any) {
  return Object.keys(obj).length === 0 && obj.constructor === Object
}