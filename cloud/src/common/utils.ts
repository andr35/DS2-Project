import {spawn} from "child_process";
import *  as chalk from "chalk";


export const ProjectUtils = {
  EC2_LOG_PATH: '/home/ubuntu/log.out',
  PROJECT_PATH: __dirname + '/../../../',
  BUILD_PATH: __dirname + '/../../../build/libs',
  JAR_NAME: 'ds2.jar',

  buildProject: (): Promise<string> => {
    return new Promise<string>((resolve, reject) => {
      console.log(chalk.blue('> Compiling project...'));

      const gradlew = spawn('./gradlew', ['node'], {
        // stdio: 'inherit',
        cwd: ProjectUtils.PROJECT_PATH
      });

      gradlew.on('close', code => {
        if (code == 0) {
          console.log(chalk.green('> Compilation success'));
          resolve(ProjectUtils.BUILD_PATH);
        } else {
          console.log(chalk.red(`> Compilation error (exit code ${code})`));
          reject(code);
        }
      });

      gradlew.on('error', (err) => {
        console.log(chalk.red('> Compilation fail'), err);
        reject(err);
      });
    });
  }
};