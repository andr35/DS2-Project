#
# This file contains some utilities to parse the reports generated from the experiments,
# compute the performances of the gossip protocol and export them as Pandas frame.
#

import json
import collections
import statistics
import re
import os
import pandas
import click
import numpy

# useful types
NodeAndReporter = collections.namedtuple('NodeAndReporter', ['node', 'reporter'])
Experiment = collections.namedtuple('Experiment', [

    # id for the experiment
    'group',
    'id',

    # repetitions
    'seed',
    'repetition',

    # settings
    'simulate_catastrophe',
    'number_of_nodes',
    'duration',
    'gossip_delta',
    'failure_delta',
    'miss_delta',
    'push_pull',
    'pick_strategy',
    'enable_multicast',
    'multicast_parameter',
    'multicast_max_wait',
    'expected_first_multicast',
    'ratio_max_wait_and_failure',
    'ratio_expected_first_multicast',
    'ratio_miss_delta',

    # statistics
    'correct',
    'n_scheduled_crashes',
    'n_expected_detected_crashes',
    'n_correctly_detected_crashes',
    'n_duplicated_reported_crashes',
    'n_wrongly_reported_crashes',
    'n_reappeared',
    'rate_detected_crashes',
    'detect_time_average',
    'detect_time_stdev',
    'detect_time_first',
    'detect_time_last'
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
    try:
        seed = current['seed']
    except KeyError:
        seed = re.search('seed-([0-9]+)', _id).group(1)
    try:
        repetition = current['repetition']
    except KeyError:
        repetition = re.search('repetition-([0-9]+)', _id).group(1)

    # load general information
    n_nodes = current['settings']['number_of_nodes']
    expected_crashes = current['result']['expected_crashes']
    reported_crashes = current['result']['reported_crashes']

    try:
        n_reappeared = len(current['result']['reappeared_nodes'])
    except KeyError:
        n_reappeared = 0

    # other parameter...
    failure_delta = current['settings']['failure_delta']
    max_wait = current['settings']['multicast_max_wait']
    if max_wait is None:
        ratio_max_wait_and_failure = 0
    else:
        ratio_max_wait_and_failure = round(max_wait / failure_delta, 2)

    try:
        expected_first_multicast = current['settings']['expected_first_multicast']
    except KeyError:
        expected_first_multicast = None
    if expected_first_multicast is None:
        ratio_expected_first_multicast = 0
    else:
        ratio_expected_first_multicast = round(expected_first_multicast / failure_delta, 2)

    miss_delta = current['settings']['miss_delta']
    if miss_delta is None:
        ratio_miss_delta = 0
    else:
        ratio_miss_delta = round(miss_delta / failure_delta, 2)

    # transform expected_crashes into a map
    expected_crashes_map = {e['node']: e['delta'] for e in expected_crashes}

    # check if all crashed are correct (and unique)
    correct_crashes = {}
    duplicated_crashes = []
    wrong_crashes = []

    # analyze the crashes
    for crash in reported_crashes:
        node = crash['node']
        reporter = crash['reporter']
        delta = crash['delta']
        key = NodeAndReporter(node=node, reporter=reporter)

        # crash is correct
        if node in expected_crashes_map and delta >= expected_crashes_map[node]:

            # a) AND not duplicated
            if key not in correct_crashes:
                correct_crashes[key] = delta

            # b) crash is correct BUT duplicated
            else:
                duplicated_crashes.append(crash)

        # c: crash is NOT correct
        else:
            wrong_crashes.append(crash)

    # [statistic]: rate of detected crashes
    n_scheduled = len(expected_crashes)

    # simulate_catastrophe --> all crashes at the same time
    if current['settings']['simulate_catastrophe']:
        n_expected_detected = n_scheduled * (n_nodes - n_scheduled)

    # normal run -> only crashed nodes should not report the others
    else:
        n_expected_detected = (n_scheduled * n_nodes) - (n_scheduled * (n_scheduled + 1) / 2)

    n_detected = len(correct_crashes)
    n_duplicated = len(duplicated_crashes)
    n_wrong = len(wrong_crashes)
    rate_detected_crashes = n_detected / n_expected_detected

    # [statistic]: correct - all crashes correctly reported
    correct = (n_expected_detected == n_detected and n_duplicated == 0 and n_wrong == 0 and n_reappeared == 0)

    # [statistic]: performances - average time to detect crashes
    # in case of duplicated, consider the first detection
    delays = []
    for key, delta in correct_crashes.items():
        node = key.node
        expected_delta = expected_crashes_map[node]
        assert delta >= expected_delta, 'expected delta is greater than delta for correctly detected crashes'
        delays.append(delta - expected_delta)
    try:
        detect_time_average = statistics.mean(delays)
    except statistics.StatisticsError:
        click.echo('WARNING: experiment "%s" has no detected failures... set average to -1' % _id)
        detect_time_average = -1
    try:
        detect_time_stdev = statistics.stdev(delays)
    except statistics.StatisticsError:
        click.echo('WARNING: experiment "%s" has only 0 or 1 detected failures... set st.dev to -1' % _id)
        detect_time_stdev = -1

    # [statistic]: first and last detection time
    if n_detected > 0:
        detections = correct_crashes.values()
        detect_time_first = min(detections)
        detect_time_last = max(detections)
    else:
        detect_time_first = -1
        detect_time_last = -1

    # return the results
    return Experiment(

        # id for the experiment
        group=group,
        id=_id,

        # repetitions
        seed=seed,
        repetition=repetition,

        # settings
        simulate_catastrophe=current['settings']['simulate_catastrophe'],
        number_of_nodes=n_nodes,
        duration=current['settings']['duration'],
        gossip_delta=current['settings']['gossip_delta'],
        failure_delta=failure_delta,
        miss_delta=current['settings']['miss_delta'],
        push_pull=current['settings']['push_pull'],
        pick_strategy=current['settings']['pick_strategy'],
        enable_multicast=current['settings']['enable_multicast'],
        multicast_parameter=current['settings']['multicast_parameter'],
        multicast_max_wait=current['settings']['multicast_max_wait'],
        expected_first_multicast=expected_first_multicast,
        ratio_max_wait_and_failure=ratio_max_wait_and_failure,
        ratio_expected_first_multicast=ratio_expected_first_multicast,
        ratio_miss_delta=ratio_miss_delta,

        # statistics
        correct=correct,
        n_scheduled_crashes=n_scheduled,
        n_expected_detected_crashes=n_expected_detected,
        n_correctly_detected_crashes=n_detected,
        n_duplicated_reported_crashes=n_duplicated,
        n_wrongly_reported_crashes=n_wrong,
        n_reappeared=n_reappeared,
        rate_detected_crashes=rate_detected_crashes,
        detect_time_average=detect_time_average,
        detect_time_stdev=detect_time_stdev,
        detect_time_first=detect_time_first,
        detect_time_last=detect_time_last
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
                group = re.search(re.escape(base_path) + re.escape(os.sep) + '?(.*)', directory_path).group(1)
                if group == '':
                    group = '.'
                path = directory_path + os.sep + file

                # analyze the single report
                current = parse_report(group, path)
                results.append(current)

    # return the result as a Pandas frame
    return pandas.DataFrame.from_records(results, columns=Experiment._fields)


def correct_mean(data):
    """
    Compute the mean over a column of numeric values,
    exclude the values -1 before the computation.
    :param data: List of numbers.
    :return: Mean.
    """
    ok = list(filter(lambda x: x != -1, data))
    if len(ok) == 0:
        return -1
    else:
        return numpy.mean(ok)
