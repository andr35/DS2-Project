#!/usr/bin/env python3

import json
import collections
import statistics
import re
import os
import pandas
import click

# useful types
NodeAndReporter = collections.namedtuple('NodeAndReporter', ['node', 'reporter'])
Experiment = collections.namedtuple('Experiment', [

    # id for the experiment
    'group',
    'id',

    # settings
    'number_of_nodes',
    'duration',
    'push_pull',
    'gossip_delta',
    'failure_delta',
    'multicast_parameter',
    'multicast_max_wait',

    # repetitions
    'seed',
    'repetition',

    # statistics
    'correct',
    'n_scheduled_crashes',
    'n_expected_detected_crashes',
    'n_correctly_detected_crashes',
    'n_duplicated_reported_crashes',
    'n_wrongly_reported_crashes',
    'rate_detected_crashes',
    'detect_time_average',
    'detect_time_stdev'
])


def parse_report(group, path):
    """
    Parse the report of a single experiment.
    :param group: Group of experiments to which this report belongs.
    :param path: Path to the report JSON file.
    :return: Summary of the experiment and computed statistics.
    """

    # load the file
    with open(path) as data_file:
        current = json.load(data_file)

    # extract repetition information
    _id = current['id']
    seed = re.search('seed-([0-9]+)', _id).group(1)
    repetition = re.search('repetition-([0-9]+)', _id).group(1)

    # load general information
    n_nodes = current['settings']['number_of_nodes']
    expected_crashes = current['result']['expected_crashes']
    reported_crashes = current['result']['reported_crashes']

    # transform expected_crashes into a map
    expected_crashes_map = {e['node']: e['delta'] for e in expected_crashes}

    # check if all crashed are correct (and unique)
    correct_crashes = {}
    duplicated_crashes = {}
    wrong_crashes = {}

    # analyze the crashes
    for crash in reported_crashes:
        node = crash['node']
        reporter = crash['reporter']
        delta = crash['delta']
        key = NodeAndReporter(node=node, reporter=reporter)

        # classify the experiment
        if node in expected_crashes_map:
            expected_delta = expected_crashes_map[node]

            # a) crash is correct AND not duplicated
            if delta >= expected_delta and key not in correct_crashes:
                correct_crashes[key] = delta

            # b) crash is correct BUT duplicated
            else:
                duplicated_crashes[key] = delta

        # c: crash is NOT correct
        else:
            wrong_crashes[key] = delta

    # [statistic]: rate of detected crashes
    n_scheduled = len(expected_crashes)
    n_expected_detected = (n_scheduled * n_nodes) - (n_scheduled * (n_scheduled + 1) / 2)
    n_detected = len(correct_crashes)
    n_duplicated = len(duplicated_crashes)
    n_wrong = len(wrong_crashes)
    rate_detected_crashes = n_detected / n_expected_detected

    # [statistic]: correct - all crashes correctly reported
    correct = (n_expected_detected == n_detected and n_duplicated == 0 and n_wrong == 0)

    # [statistic]: performances - average time to detect crashes
    delays = []
    for key, delta in correct_crashes.items():
        node = key.node
        expected_delta = expected_crashes_map[node]
        assert delta >= expected_delta, 'expected delta is greater than delta for correctly detected crashes'
        delays.append(delta - expected_delta)
    detect_time_average = statistics.mean(delays)
    detect_time_stdev = statistics.stdev(delays)

    # return the results
    return Experiment(

        # id for the experiment
        group=group,
        id=_id,

        # settings
        number_of_nodes=n_nodes,
        duration=current['settings']['duration'],
        push_pull=current['settings']['push_pull'],
        gossip_delta=current['settings']['gossip_delta'],
        failure_delta=current['settings']['failure_delta'],
        multicast_parameter=current['settings']['multicast_parameter'],
        multicast_max_wait=current['settings']['multicast_max_wait'],

        # repetitions
        seed=seed,
        repetition=repetition,

        # statistics
        correct=correct,
        n_scheduled_crashes=n_scheduled,
        n_expected_detected_crashes=n_expected_detected,
        n_correctly_detected_crashes=n_detected,
        n_duplicated_reported_crashes=n_duplicated,
        n_wrongly_reported_crashes=n_wrong,
        rate_detected_crashes=rate_detected_crashes,
        detect_time_average=detect_time_average,
        detect_time_stdev=detect_time_stdev
    )


def analyze_results(base_path):
    """
    Analyze the results of all experiments.
    :param base_path: Base directory where to find all reports.
    :return: Pandas Frame with all the results.
    """

    # collect the parsed results
    results = []

    # explore all directories
    for (directory_path, _, files) in os.walk(base_path):
        for file in files:
            if file.endswith('.json'):
                group = re.search(re.escape(base_path) + re.escape(os.sep) + '(.+)', directory_path).group(1)
                path = directory_path + os.sep + file

                # analyze the single report
                current = parse_report(group, path)
                results.append(current)

    # return the result as a Pandas frame
    return pandas.DataFrame.from_records(results, columns=Experiment._fields)


@click.command()
@click.option('--reports-path', help='Base path where to find the reports.', prompt=True)
@click.option('--output-path', help='Directory where to store the result of the analysis.', prompt=True)
def main(reports_path, output_path):
    """
    Analyze the results of the experiments and produces
    useful plots to include in the final report.
    """

    # create directory for the results
    if not os.path.exists(output_path):
        os.makedirs(output_path)

    # analyze the results
    frame = analyze_results(reports_path)
    frame.to_csv(output_path + os.sep + 'results.csv', index=False)

    # TODO: plots


# entry point for the script
if __name__ == '__main__':
    main()
