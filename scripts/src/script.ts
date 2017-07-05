import * as inquirer from 'inquirer';
import * as chalk from 'chalk';
import * as program from 'commander';
import {LocalStart} from "./local-start";
import {AwsStart} from "./aws-start";

export class Script {

  constructor() {
    this.init();
  }

  private  init() {

    program
      .version('1.0.0')
      .option('-n --nodes <n>', 'Number of nodes to use in the experiment', parseNumericOption)
      .option('-d --duration <n>', 'Duration of an experiment (in seconds)', parseNumericOption)
      .option('-e --experiments <n>', 'Number of experiments', parseNumericOption)
      .option('-k --repetitions <n>', 'Repeat experiment n times', parseNumericOption)
      .option('-s --initial-seed <n>', 'Initial seed', parseNumericOption)
      .option('-r --report-path <n>', 'Report path for the tracker');

    program
      .command('spawn')
      .description('Spawn machines on cloud')
      .action(options => this.spawn(options.parent));

    program
      .command('start <location>')
      .description('Start an experiment (in local / aws)')
      .action((extraArg: string, options: any) => this.start(extraArg, options.parent));

    program
      .command('shutdown')
      .description('Shutdown machines on cloud')
      .action(options => this.shutdown());

    program
      .command('report')
      .description('Download reports from a tracker')
      .action(options => this.report());
  }

  public run() {
    program.parse(process.argv);

    if (program.args.length === 0) {
      program.help();
    }
  }

  // /////////////////////////
  //  Commands
  // /////////////////////////

  private spawn(options: any) {
    this.completeTrackerOptions(options).then(options => {
      console.info(chalk.bold.blue('> Spawn cloud machines...'));
      // TODO
    }).catch(err => Script.printErrorAndExit(err));
  }

  private start(extraArg: string, options: any) {
    this.completeTrackerOptions(options).then(options => {
      switch (extraArg) {
        case 'local':
          console.info(chalk.bold.blue('> Start machines in local environment...'));
          new LocalStart(options).run();
          break;
        case 'aws':
          console.info(chalk.bold.blue('> Start cloud machines...'));
          new AwsStart(options).run();
          break;
        default:
          Script.printErrorAndExit('Not a valid location for start system.');
      }
    }).catch(err => Script.printErrorAndExit(err));
  }

  private shutdown() {
    console.info(chalk.bold.blue('> Shutdown cloud machines...'));
    // TODO
  }

  private report() {
    console.info(chalk.bold.blue('> Download report from tracker...'));
    // TODO
  }

  private completeTrackerOptions(options: any): Promise<any> {
    return new Promise((resolve, reject) => {
      const prompts = [];
      // Check  which option are missing ////////////////////////////////
      if (!Script.isOptionPassed(options, 'nodes')) {
        prompts.push({
          type: 'input',
          name: 'nodes',
          message: 'How many nodes should be used for the experiment?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!Script.isOptionPassed(options, 'duration')) {
        prompts.push({
          type: 'input',
          name: 'duration',
          message: 'Duration of an experiment?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!Script.isOptionPassed(options, 'experiments')) {
        prompts.push({
          type: 'input',
          name: 'experiments',
          message: 'Number of experiments?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!Script.isOptionPassed(options, 'repetitions')) {
        prompts.push({
          type: 'input',
          name: 'repetitions',
          message: 'How many time repeat the experiments?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!Script.isOptionPassed(options, 'initialSeed')) {
        prompts.push({
          type: 'input',
          name: 'initialSeed',
          message: 'Initial seed?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!Script.isOptionPassed(options, 'reportPath')) {
        prompts.push({
          type: 'input',
          name: 'reportPath',
          message: 'Report path?'
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

  static isOptionPassed(options: any, option: string): boolean {
    return !!options[option];
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