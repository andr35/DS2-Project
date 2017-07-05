import * as aws from 'aws-sdk';
import {EC2} from 'aws-sdk';
import {RunInstancesRequest} from "aws-sdk/clients/ec2";

export class AwsStart {

  ec2: EC2;

  constructor(private options: any) {
    aws.config.loadFromPath(`${__dirname}/../aws.config.json`);
    this.ec2 = new aws.EC2();
  }

  run() { // eu-west-1a
    const params: RunInstancesRequest = {
      ImageId: 'ami-d15a75c7', // ami-d7b9a2b1 amazon // ami-6d48500b ubuntu // ami-d15a75c7 ubuntu
      InstanceType: 't2.micro',
      MinCount: 1, // Number of instances
      MaxCount: 1,
    };

    this.createInstances(params)
      .then(ids => {
        console.log('Instances created', ids);

      })
      .catch(err => console.error(err));
  }

  private createInstances(params: RunInstancesRequest): Promise<string[]> {
    return new Promise((resolve, reject) => {
      this.ec2.runInstances(params, (err, data) => {
        if (err) {
          return reject(err);
        }
        const instanceIds = data.Instances.map(i => i.InstanceId);
        resolve(instanceIds);

        // Add tags to the instance
        let tagParams = {
          Resources: instanceIds,
          Tags: [{Key: 'Name', Value: 'SDK Sample'}]
        };
        this.ec2.createTags(tagParams, function (err) {
          console.log("Tagging instance:", err ? "failure" : "success");
        });
      });
    });
  }

  private startInstances(instanceIds: string[]) {

    const params = {
      InstanceIds: instanceIds,
      DryRun: true
    };

    this.ec2.startInstances(params, (err, data) => {
      if (err && err.code === 'DryRunOperation') {
        params.DryRun = false;
        this.ec2.startInstances(params, function (err, data) {
          if (err) {
            console.log("Error", err);
          } else if (data) {
            console.log("Success", data.StartingInstances);
          }
        });
      } else {
        console.log("You don't have permission to start instances.");
      }
    });
  }

}