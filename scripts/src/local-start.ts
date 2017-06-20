import {spawn} from "child_process";
import * as chalk from "chalk";

export class LocalStart {

  static BUILD_PATH = __dirname + '/../../build/libs';
  static JAR_NAME = 'ds2.jar';

  constructor(private options: any) {
  }

  run() {
    const spawnOptions = {
      stdio: 'inherit',
      cwd: LocalStart.BUILD_PATH
    };

    console.info(chalk.bold.green('> Start Tracker node...'));
    const tracker = spawn('java', ['-jar', LocalStart.JAR_NAME, 'tracker'], {
      ...spawnOptions, env: {
        NODES: this.options['nodes'],
        DURATION: this.options['duration'],
        EXPERIMENTS: this.options['experiments'],
        INITIAL_SEED: this.options['initial-seed'],
        REPORT_PATH: this.options['report-path']
      }
    });

    let i = 0;
    console.info(chalk.bold.green('> Start Nodes every 2sec...'));
    const interval = setInterval(() => {
      if (i >= this.options['nodes']) {
        clearInterval(interval);
      } else {
        i++;
        console.info(chalk.bold.green(`> Start Node [${i}]...`));
        spawn('java', ['-jar', LocalStart.JAR_NAME, 'node', '127.0.0.1', '10000'], {
          ...spawnOptions, env: {ID: i, PORT: (10000 + i)}
        });
      }
    }, 2000);
  }
}