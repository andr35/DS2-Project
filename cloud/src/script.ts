import * as inquirer from 'inquirer';
import * as chalk from 'chalk';
import * as program from 'commander';
import {Options} from "./common/options";
import {LocalMachine} from "./cloud/local-machine";
import {AwsCloud} from "./cloud/aws-cloud";

export class Script {

  constructor() {
    this.init();
  }

  private  init() {

    program
      .version('1.0.0')
      .option('-k --keys <n>', 'Keys json file')
      .option('-s --ssh-key <n>', 'Ssh key to access to remote machine')
      .option('-p --ssh-passphrase <n>', 'Ssh key password');

    program
      .command('start <experiment-name>')
      .description('Start an experiment (in local / aws)')
      .option('-n --nodes <n>', 'Number of nodes to use in the experiment', parseNumericOption)
      .option('-d --duration <n>', 'Duration of an experiment (in milliseconds)', parseNumericOption)
      .option('-e --experiments <n>', 'Number of experiments', parseNumericOption)
      .option('-z --repetitions <n>', 'Repeat experiment n times', parseNumericOption)
      .option('-i --initial-seed <n>', 'Initial seed', parseNumericOption)
      .option('-t --time-between-experiments <n>', 'Time between experiments', parseNumericOption)
      .option('-m --min-failure-rounds <n>', 'Min number of rounds of gossip after which a node should be considered failed', parseNumericOption)
      .option('-o --max-failure-rounds <n>', 'Max number of rounds of gossip after which a node should be considered failed', parseNumericOption)
      .option('-f --miss-delta-rounds <n>', 'Number of delta rounds to calculate the miss time', parseNumericOption)
      .option('-r --report-path <n>', 'Report path for the tracker')
      .option('-l --local', 'Start experiment locally')
      .action((extraArg: string, options: any) => this.start(extraArg, {...options, ...options.parent}));

    program
      .command('shutdown [experiment-name]')
      .description('Shutdown machines on cloud')
      .action((extraArg: string, options: any) => this.shutdown(extraArg, {...options, ...options.parent}));

    program
      .command('report <experiment-name>')
      .description('Download reports from a tracker')
      .option('-d --download-dir <path>', 'Directory where put downloaded reports')
      .option('-r --report-dir <path>', 'Directory where reports are stored on the remote machine')
      .action((extraArg: string, options: any) => this.report(extraArg, {...options, ...options.parent}));

    program
      .command('watch <experiment-name>')
      .description('Download reports from a tracker')
      .action((extraArg: string, options: any) => this.watch(extraArg, {...options, ...options.parent}));

    program
      .command('list')
      .description('List all running machines')
      .action((options: any) => this.list({...options, ...options.parent}));
  }

  //noinspection JSMethodCanBeStatic
  public run() {
    program.parse(process.argv);

    if (program.args.length === 0) {
      program.help();
    }
  }

  // /////////////////////////////////////////////////
  //  Commands
  // /////////////////////////////////////////////////

  private start(experimentName: string, options: Options) {

    this.completeTrackerOptions(options).then(options => {
      if (options.local) {
        // Run experiment locally
        console.info(chalk.bold.blue('> Start machines in local environment...'));
        new LocalMachine(options).startExperiment();
      } else { // Run experiment on AWS
        // Check for options
        Script.checkCloudKeysPassed(options);
        Script.checkSshKeysPassed(options);
        console.info(chalk.bold.blue('> Start cloud machines...'));
        new AwsCloud(experimentName, options).startExperiment();
      }
    }).catch(err => Script.printErrorAndExit(err));
  }

  //noinspection JSMethodCanBeStatic
  private shutdown(experimentName: string, options: Options) {
    // Check for options
    Script.checkCloudKeysPassed(options);
    console.info(chalk.bold.blue('> Shutdown cloud machines...'));
    new AwsCloud(experimentName, options).shutdown();
  }

  //noinspection JSMethodCanBeStatic
  private report(experimentName: string, options: Options) {
    // Check for options
    Script.checkDownloadDirPassed(options);
    Script.checkCloudKeysPassed(options);
    Script.checkSshKeysPassed(options);

    // Do
    console.info(chalk.bold.blue('> Download report from tracker...'));
    new AwsCloud(experimentName, options).downloadReport();
  }

  //noinspection JSMethodCanBeStatic
  private watch(experimentName: string, options: Options) {
    // Check for options
    Script.checkCloudKeysPassed(options);
    Script.checkSshKeysPassed(options);

    console.info(chalk.bold.blue('> Watching tracker logs...'));
    new AwsCloud(experimentName, options).watchTrackerLogs();
  }

  //noinspection JSMethodCanBeStatic
  private list(options: Options) {
    // Check for options
    Script.checkCloudKeysPassed(options);
    console.info(chalk.bold.blue('> Listing cloud machines...'));
    new AwsCloud(null, options).runningMachines();
  }

  // /////////////////////////////////////////////////
  //  Utilities
  // /////////////////////////////////////////////////

  private completeTrackerOptions(options: Options): Promise<Options> {
    return new Promise((resolve, reject) => {
      const prompts = [];
      // Check  which option are missing ////////////////////////////////
      if (!options.nodes) {
        prompts.push({
          type: 'input',
          name: 'nodes',
          message: 'How many nodes should be used for the experiment?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!options.duration) {
        prompts.push({
          type: 'input',
          name: 'duration',
          message: 'Duration of an experiment (milliseconds)?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!options.experiments) {
        prompts.push({
          type: 'input',
          name: 'experiments',
          message: 'Number of experiments?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!options.minFailureRounds) {
        prompts.push({
          type: 'input',
          name: 'minFailureRounds',
          message: 'Min number of rounds of gossip after which a node should be considered failed?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!options.maxFailureRounds) {
        prompts.push({
          type: 'input',
          name: 'maxFailureRounds',
          message: 'Max number of rounds of gossip after which a node should be considered failed?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!options.missDeltaRounds) {
        prompts.push({
          type: 'input',
          name: 'missDeltaRounds',
          message: 'Number of delta rounds to calculate the miss time?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!options.initialSeed || options.initialSeed < 0) {
        prompts.push({
          type: 'input',
          name: 'initialSeed',
          message: 'Initial seed?',
          validate: input => greaterThanZeroIncluded(input)
        });
      }

      // Ask for missing options ///////////////////////////////////////
      if (prompts.length > 0) {
        return inquirer.prompt(prompts)
          .then(answer => resolve({...options, ...answer}))
          .catch(err => reject(err));
      } else {
        return resolve(options);
      }
    });
  }

  private static checkCloudKeysPassed(options: Options) {
    if (!options.keys) {
      console.error(chalk.red('Missing cloud keys. Provide them using "--keys" option.'));
      process.exit(-1);
    }
  }

  private static checkSshKeysPassed(options: Options) {
    if (!options.keys) {
      console.error(chalk.red('Missing ssh keys to access remote machines. Provide them using "--ssh-key" option.'));
      process.exit(-1);
    }
  }

  private static checkDownloadDirPassed(options: Options) {
    if (!options.downloadDir) {
      console.error(chalk.red('Missing download directory. Provide them using "--download-dir" option.'));
      process.exit(-1);
    }
    if (!options.reportDir) {
      const repDir = '/tmp/gossip-style-failure-detector';
      console.log(chalk.yellow('i No report directory provided with "--report-dir" option. Use default ' + repDir));
      options.reportDir = repDir;
    }
  }

  static printErrorAndExit(err: any) {
    console.error(chalk.bold.red('An error occurred:'), err);
    process.exit(-1);
  }
}

function parseNumericOption(value: any) {
  const n = parseInt(value);
  return (isNaN(n) || n <= 0) ? 0 : n;
}

function greaterThanZero(value: string) {
  if (!( +value % 1 === 0)) {
    return 'Value is not an integer';
  }
  if (+value <= 0) {
    return 'Value must be greater than zero.';
  }
  return true;
}

function greaterThanZeroIncluded(value: string) {
  if (!( +value % 1 === 0)) {
    return 'Value is not an integer';
  }
  return true;
}