#!/usr/bin/env python3

import json
import collections
import statistics
import re
import os
import pandas
import click
import itertools
import numpy as np
import matplotlib as mpl

mpl.use('Agg')
import matplotlib.pyplot as plt

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
    'ratio_expected_first_multicast_and_failure',

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
        ratio_expected_first_multicast_and_failure = 0
    else:
        ratio_expected_first_multicast_and_failure = round(expected_first_multicast / failure_delta, 2)

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
    correct = (n_expected_detected == n_detected and n_duplicated == 0 and n_wrong == 0)

    # [statistic]: performances - average time to detect crashes
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
        ratio_expected_first_multicast_and_failure=ratio_expected_first_multicast_and_failure,

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
                group = re.search(re.escape(base_path) + re.escape(os.sep) + '?(.*)', directory_path).group(1)
                if group == '':
                    group = '.'
                path = directory_path + os.sep + file

                # analyze the single report
                current = parse_report(group, path)
                results.append(current)

    # return the result as a Pandas frame
    return pandas.DataFrame.from_records(results, columns=Experiment._fields)


def my_mean(data):
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
        return np.mean(ok)


def plot_average_detect_time(path, frame):
    """
    Plot a graph with the relationship between failure-time and performances.
    :param path: Path where to store the plot.
    :param frame: Pandas frame with all the data.
    """

    # x axis
    x_axis = ['failure_delta']

    # do not aggregate
    aggregate_none = ['id', 'group', 'seed', 'repetition', 'multicast_max_wait', 'miss_delta', 'multicast_parameter',
                      'expected_first_multicast']

    # aggregate, plot on the same graph
    aggregate_same = ['push_pull']

    # aggregate, on different plots
    aggregate_different = ['number_of_nodes', 'simulate_catastrophe', 'n_scheduled_crashes',
                           'duration', 'gossip_delta', 'enable_multicast', 'pick_strategy',
                           'ratio_max_wait_and_failure', 'ratio_expected_first_multicast_and_failure']

    # ignored fields -> these are the statistics
    stats = ['correct', 'n_expected_detected_crashes', 'n_correctly_detected_crashes',
             'n_duplicated_reported_crashes', 'n_wrongly_reported_crashes', 'rate_detected_crashes',
             'detect_time_average', 'detect_time_stdev']

    # security check
    missing = set(frame.columns.values) - set(x_axis + aggregate_none + aggregate_same + aggregate_different + stats)
    if len(missing) != 0:
        click.echo("ERROR: some fields are neither set to be aggregated nor to be ignored -> " + str(missing))

    # aggregate
    aggregation = frame.groupby(x_axis + aggregate_same + aggregate_different, as_index=False).agg(
        {
            'correct': {
                'aggregated_correct': (lambda column: False not in list(column))
            },
            'detect_time_average': {
                'aggregated_detect_time_average': my_mean
            },
            'n_duplicated_reported_crashes': {
                'n_duplicated_reported_crashes': 'mean'
            },
            'n_wrongly_reported_crashes': {
                'n_wrongly_reported_crashes': 'mean'
            }
        }
    )
    aggregation.columns = aggregation.columns.droplevel(1)

    # collect different unique values of the fields that I want to aggregate in different plots
    aggregate_different_unique_values = []
    for field in aggregate_different:
        aggregate_different_unique_values.append(aggregation[field].unique())

    # different plots for each unique combination
    for combination in itertools.product(*aggregate_different_unique_values):

        # compute the name for the plot
        name = '__'.join(map(lambda t: '%s-%s' % (t[0], t[1]), zip(aggregate_different, combination)))

        # compute the correct projection on the table
        data = None
        for index, value in enumerate(combination):
            data = (data if data is not None else aggregation).query('%s == %s' % (aggregate_different[index], value))

        # extract the gossip delta, used to scale the plots axes
        delta = combination[aggregate_different.index('gossip_delta')]
        if len(data) > 0:

            # extract the number of nodes for the legend
            nodes = combination[aggregate_different.index('number_of_nodes')]
            strategy = combination[aggregate_different.index('pick_strategy')]

            # create the plot
            figure = plt.figure()
            ax = figure.add_subplot(111)

            # lines -> combinations of push_pull vs pick_strategy
            aggregate_same_unique_values = []
            for field in aggregate_same:
                aggregate_same_unique_values.append(data[field].unique())
            for correct in [True, False]:
                for tt in itertools.product(*aggregate_same_unique_values):
                    qq = ' and '.join(map(lambda t: '%s == %s' % (t[0], t[1]), zip(aggregate_same, tt)))
                    ff = data.query('correct == %s' % correct).query('%s' % qq)
                    trace = (ff['failure_delta'], ff['detect_time_average'])
                    push_pull = 'push_pull' if tt[aggregate_same.index('push_pull')] else 'push'
                    correct_label = 'correct' if correct else 'wrong'
                    ax.plot(trace[0] / delta, trace[1] / delta,
                            label='%s [%s]' % (push_pull, correct_label),
                            marker='o' if correct else 'x')

            # labels, title, axes
            ax.legend(shadow=True)
            ax.set_title('Average Detection Time (n=%d, tg=%.1fs, st=%s)' % (nodes, delta / 1000, strategy))
            ax.set_xlabel('Failure Time (rounds of gossip)')
            ax.set_ylabel('Detection Time (rounds of gossip)')
            ax.tick_params(axis='both', which='major')
            ax.grid(True)

            # save plot
            figure.savefig(path + name + '.png', bbox_inches='tight')
            plt.close(figure)


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

    # plot 1: average detection time
    plot_average_detect_time(output_path + os.sep, frame)


# entry point for the script
if __name__ == '__main__':
    main()
