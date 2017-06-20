import * as inquirer from 'inquirer';
import * as chalk from 'chalk';
import * as commandLineArgs from 'command-line-args';
import {LocalStart} from "./local-start";

export class Script {

  static CLI_OPTIONS = [
    {name: 'nodes', alias: 'n', type: Number, description: 'Number of nodes to use in the experiment'},
    {name: 'duration', alias: 'd', type: Number, description: 'Duration of an experiment (in seconds)'},
    {name: 'experiments', alias: 'e', type: Number, description: 'Number of experiments'},
    {name: 'repetitions', alias: 'k', type: Number, description: 'Repeat experiment n times'},
    {name: 'initial-seed', alias: 's', type: Number, description: 'Initial seed'},
    {name: 'report-path', alias: 'r', type: String, description: 'Report path for the tracker'},
    {name: 'local', alias: 'l', type: Boolean, description: 'Start local nodes and tracker'}
  ];

  static CLI_COMMANDS = [
    {name: 'spawn', alias: 'c', description: 'Spawn machines on cloud'},
    {name: 'start', alias: 's', description: 'Start an experiment'},
    {name: 'shutdown', alias: 'sd', description: 'Shutdown machines on cloud'},
    {name: 'report', alias: 'r', description: 'Download reports from a tracker'},
    {name: 'help', alias: 'h', description: 'Show help message'}
  ];

  command: string;
  options: any;

  constructor() {
    this.init();
  }

  private  init() {
    try {
      this.options = commandLineArgs(Script.CLI_OPTIONS);
    } catch (e) {
      console.error(chalk.bold.red('> Some of the arguments are not valid or unknown.'));
      this.showHelp();
      process.exit(-1);
    }
    this.command = process.argv[2];
  }

  private parseCommand(): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      if (this.command && (Script.CLI_COMMANDS.map(c => c.name).indexOf(this.command) === -1)) {
        // Command passed but it is unknown
        console.error(chalk.bold.red('> Invalid command. Please provide a valid command.'));
        this.showHelp();
        process.exit(-1);
      }

      if (!this.command) { // Command missing
        inquirer.prompt([{
          type: 'list',
          name: 'command',
          message: 'What do you want to do?',
          choices: Script.CLI_COMMANDS.map(c => c.name)
        }])
          .then(answer => resolve(answer.command))
          .catch(err => reject(err));
      } else {
        return resolve(this.command);
      }
    });
  }

  run() {
    this.parseCommand()
      .then(command => {
        this.command = command;

        switch (this.command) {
          case 'spawn':
            this.getTrackerOptions()
              .then(res => {
                this.options = res;
                console.info(chalk.bold.blue('> Spawn cloud machines...'));
                // TODO
              }).catch(err => {
              console.error(chalk.bold.red('An error occurred:'), err);
              process.exit(-1);
            });
            break;
          case 'start':
            console.info(chalk.bold.blue('> Start experiments...'));
            this.getTrackerOptions()
              .then(res => {
                this.options = res;
                if (this.options['local']) {
                  console.info(chalk.bold.blue('> Start machines in local environment...'));
                  new LocalStart(this.options).run();
                } else {
                  console.info(chalk.bold.blue('> Start cloud machines...'));
                  // TODO
                }
              }).catch(err => {
              console.error(chalk.bold.red('An error occurred:'), err);
              process.exit(-1);
            });
            break;
          case 'shutdown':
            console.info(chalk.bold.blue('> Shutdown cloud machines...'));
            // TODO
            break;
          case 'report':
            console.info(chalk.bold.blue('> Download report from tracker...'));
            // TODO
            break;
          case 'help':
            this.showHelp();
            break;
          default:
            this.showHelp();
        }
      })
      .catch(err => {
        console.error(chalk.bold.red('An error occurred:'), err);
        process.exit(-1);
      });
  }

  getTrackerOptions() {
    return new Promise((resolve, reject) => {
      const prompts = [];

      if (!this.isOptionPassed('nodes', greaterThanZero)) {
        prompts.push({
          type: 'input',
          name: 'nodes',
          message: 'How many nodes should be used for the experiment?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!this.isOptionPassed('duration', greaterThanZero)) {
        prompts.push({
          type: 'input',
          name: 'duration',
          message: 'Duration of an experiment?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!this.isOptionPassed('experiments', greaterThanZero)) {
        prompts.push({
          type: 'input',
          name: 'experiments',
          message: 'Number of experiments?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!this.isOptionPassed('repetitions', greaterThanZero)) {
        prompts.push({
          type: 'input',
          name: 'repetitions',
          message: 'How many time repeat the experiments?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!this.isOptionPassed('initial-seed', greaterThanZero)) {
        prompts.push({
          type: 'input',
          name: 'initial-seed',
          message: 'Initial seed?',
          validate: input => greaterThanZero(input)
        });
      }

      if (!this.isOptionPassed('report-path')) {
        prompts.push({
          type: 'input',
          name: 'report-path',
          message: 'Report path?'
        });
      }

      if (prompts.length > 0) {
        return inquirer.prompt(prompts)
          .then(answer => resolve({...this.options, ...answer}))
          .catch(err => reject(err));
      } else {
        return resolve(this.options);
      }
    });
  }

  isOptionPassed(option: string, validate?: Function): any {
    const opt = this.options[option];
    if (opt) {
      if (validate) {
        const msg = validate(opt);
        if (msg !== true) {
          console.error(chalk.bold.red(`> Option "${option}" is not valid. Motivation: "${msg}". Value: "${opt}".`));
          process.exit(-1);
        }
      }
      return opt;
    }
    return null;
  }

  showHelp() {
    console.info(`Usage: ${process.argv[1]} [command] [options]\n`);
    console.info('Commands:');
    Script.CLI_COMMANDS.forEach((cmd) => {
      console.info(`\t${chalk.yellow(cmd.name + ', ' + cmd.alias)}\t${cmd.description}`)
    });
    console.info('\nOptions:');
    Script.CLI_OPTIONS.forEach((opt) => {
      console.info(`\t${chalk.yellow('-' + opt.alias + ', --' + opt.name)}\t${opt.description}`)
    });
  }

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