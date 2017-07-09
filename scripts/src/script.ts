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
      .command('start <location>')
      .description('Start an experiment (in local / aws)')
      .option('-n --nodes <n>', 'Number of nodes to use in the experiment', parseNumericOption)
      .option('-d --duration <n>', 'Duration of an experiment (in milliseconds)', parseNumericOption)
      .option('-e --experiments <n>', 'Number of experiments', parseNumericOption)
      .option('-z --repetitions <n>', 'Repeat experiment n times', parseNumericOption)
      .option('-i --initial-seed <n>', 'Initial seed', parseNumericOption)
      .option('-r --report-path <n>', 'Report path for the tracker')
      .action((extraArg: string, options: any) => this.start(extraArg, {...options, ...options.parent}));

    program
      .command('shutdown <location>')
      .description('Shutdown machines on cloud')
      .action((extraArg: string, options: any) => this.shutdown(extraArg, {...options, ...options.parent}));

    program
      .command('report <location>')
      .description('Download reports from a tracker')
      .option('-d --download-dir <n>', 'Directory where put downloaded reports')
      .option('-r --report-dir <n>', 'Directory where reports are stored on the remote machine')
      .action((extraArg: string, options: any) => this.report(extraArg, {...options, ...options.parent}));

    program
      .command('watch <location>')
      .description('Download reports from a tracker')
      .action((extraArg: string, options: any) => this.watch(extraArg, {...options, ...options.parent}));
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

  private start(location: string, options: Options) {

    this.completeTrackerOptions(options).then(options => {
      switch (location) {
        case 'local':
          console.info(chalk.bold.blue('> Start machines in local environment...'));
          new LocalMachine(options).startExperiment();
          break;
        case 'aws':
          // Check for options
          Script.checkCloudKeysPassed(options);
          Script.checkSshKeysPassed(options);
          console.info(chalk.bold.blue('> Start cloud machines...'));
          new AwsCloud(options).startExperiment();
          break;
        default:
          Script.printWrongLocationEndExit();
      }
    }).catch(err => Script.printErrorAndExit(err));
  }

  //noinspection JSMethodCanBeStatic
  private shutdown(location: string, options: Options) {
    // Check for options
    Script.checkCloudKeysPassed(options);

    console.info(chalk.bold.blue('> Shutdown cloud machines...'));

    switch (location) {
      case 'aws':
        new AwsCloud(options).shutdown();
        break;
      default:
        Script.printWrongLocationEndExit();
    }
  }

  //noinspection JSMethodCanBeStatic
  private report(location: string, options: Options) {
    // Check for options
    Script.checkDownloadDirPassed(options);
    Script.checkCloudKeysPassed(options);
    Script.checkSshKeysPassed(options);

    // Do
    console.info(chalk.bold.blue('> Download report from tracker...'));
    switch (location) {
      case 'aws':
        new AwsCloud(options).downloadReport();
        break;
      default:
        Script.printWrongLocationEndExit();
    }

  }

  //noinspection JSMethodCanBeStatic
  private watch(location: string, options: Options) {
    // Check for options
    Script.checkCloudKeysPassed(options);
    Script.checkSshKeysPassed(options);

    console.info(chalk.bold.blue('> Watching tracker logs...'));
    switch (location) {
      case 'aws':
        new AwsCloud(options).watchTrackerLogs();
        break;
      default:
        Script.printWrongLocationEndExit();
    }

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

      if (!options.repetitions) {
        prompts.push({
          type: 'input',
          name: 'repetitions',
          message: 'How many time repeat the experiments?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!options.initialSeed) {
        prompts.push({
          type: 'input',
          name: 'initialSeed',
          message: 'Initial seed?',
          validate: input => greaterThanZero(input)
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

  static printWrongLocationEndExit() {
    console.error(chalk.bold.red('Must provide a valid <location> argument.'));
    process.exit(-1);
  }

  static printErrorAndExit(err: any) {
    console.error(chalk.bold.red('An error occurred:'), err);
    process.exit(-1);
  }
}

function parseNumericOption(value: any) {
  const n = parseInt(value);
  return (isNaN(n) || n <= 0) ? undefined : n;
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