#!/usr/bin/env python3

import json
import collections
import statistics
import re
import os
import pandas
import click
import matplotlib as mpl

mpl.use('Agg')
import matplotlib.pyplot as plt

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
                group = re.search(re.escape(base_path) + re.escape(os.sep) + '?(.*)', directory_path).group(1)
                if group == '':
                    group = '.'
                path = directory_path + os.sep + file

                # analyze the single report
                current = parse_report(group, path)
                results.append(current)

    # return the result as a Pandas frame
    return pandas.DataFrame.from_records(results, columns=Experiment._fields)


def plot_average_detect_time(path, frame):
    """
    Plot a graph with the relationship between failure-time and performances.
    :param path: Path where to store the plot.
    :param frame: Pandas frame with all the data.
    """

    # aggregate
    aggregation = frame.groupby(['push_pull', 'number_of_nodes', 'gossip_delta', 'failure_delta'], as_index=False).agg(
        {
            'correct': {
                'aggregated_correct': (lambda column: False not in list(column))
            },
            'detect_time_average': {
                'aggregated_detect_time_average': 'sum'
            }
        }
    )
    aggregation.columns = aggregation.columns.droplevel(1)

    # different plots for different number of nodes and gossip deltas
    number_of_nodes = aggregation['number_of_nodes'].unique()
    gossip_deltas = aggregation['gossip_delta'].unique()
    for nodes in number_of_nodes:
        for delta in gossip_deltas:
            data = aggregation.query('number_of_nodes == %d' % nodes).query('gossip_delta == %d' % delta)

            # create the plot
            figure = plt.figure()
            ax = figure.add_subplot(111)

            # 2 lines: PUSH and PUSH_PULL
            push_frame_ok = data.query('correct == True and push_pull == False')
            push_ok = (push_frame_ok['failure_delta'], push_frame_ok['detect_time_average'])
            push_pull_frame_ok = data.query('correct == True and push_pull == True')
            push_pull_ok = (push_pull_frame_ok['failure_delta'], push_pull_frame_ok['detect_time_average'])

            push_frame_ko = data.query('correct == False and push_pull == False')
            push_ko = (push_frame_ko['failure_delta'], push_frame_ko['detect_time_average'])
            push_pull_frame_ko = data.query('correct == False and push_pull == True')
            push_pull_ko = (push_pull_frame_ko['failure_delta'], push_pull_frame_ko['detect_time_average'])

            ax.plot(push_ok[0] / 1000, push_ok[1] / 1000, label='push [correct]', marker='o')
            ax.plot(push_ko[0] / 1000, push_ko[1] / 1000, label='push [wrong]', marker='x')
            ax.plot(push_pull_ok[0] / 1000, push_pull_ok[1] / 1000, label='push_pull [correct]', marker='o')
            ax.plot(push_pull_ko[0] / 1000, push_pull_ko[1] / 1000, label='push_pull [wrong]', marker='x')

            # labels, title, axes
            ax.legend(shadow=True)
            ax.set_title('Average Detection Time (n = %d, t_gossip = %.1f s)' % (nodes, delta / 1000))
            ax.set_xlabel('Failure Time (s)')
            ax.set_ylabel('Detection Time (s)')
            ax.tick_params(axis='both', which='major')
            ax.grid(True)

            # save plot
            figure.savefig(path + 'detect_time__nodes-%d__gossip_delta-%d.png' % (nodes, delta), bbox_inches='tight')
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
