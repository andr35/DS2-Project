#!/usr/bin/env python3

#
# This file generates the plots included in the report.
#

import os
import re
import click
import pandas
import matplotlib as mpl

from parsey import analyze_results, correct_mean

mpl.use('Agg')
import matplotlib.pyplot as plt


def plot(frame, path, group, strategy, catastrophe, multicast):
    """
    Generate the first plot present in the report.
    It compares push and push_pull with different failure times.
    :param frame: Pandas frame with all the data.
    :param path: Base path where to store the plot.
    """

    # filter
    data = frame[frame['group'].str.match('.*%s.*' % re.escape(group))] \
        .query('pick_strategy == %d' % strategy) \
        .query('simulate_catastrophe == %s' % catastrophe) \
        .query('enable_multicast == %s' % multicast)

    # aggregate
    aggregation = data.groupby(['failure_delta', 'push_pull', 'gossip_delta'], as_index=False).agg(
        {
            'correct': {
                'aggregated_correct': (lambda column: False not in list(column))
            },
            'detect_time_average': {
                'aggregated_detect_time_average': correct_mean
            }
        }
    )
    aggregation.columns = aggregation.columns.droplevel(1)

    # extract the gossip delta
    deltas = aggregation['gossip_delta'].unique()
    assert len(deltas) == 1
    delta = deltas[0]

    # colors (push_pull, correct -> color)
    colors = {
        (True, True): 'g',
        (True, False): 'r',
        (False, True): 'b',
        (False, False): mpl.colors.CSS4_COLORS['darkorange']
    }

    # plot
    figure = plt.figure()
    ax = figure.add_subplot(111)
    for push_pull in [True, False]:
        for correct in [True, False]:
            trace = aggregation.query('push_pull == %s' % push_pull).query('correct == %s' % correct)
            ax.plot(
                trace['failure_delta'] / delta,
                trace['detect_time_average'] / delta,
                label='%s - %s' % ('PushPull' if push_pull else 'Push', 'correct' if correct else 'wrong'),
                marker='o' if correct else 'x',
                linestyle=('-' if push_pull else '-.'),
                color=colors[(push_pull, correct)],
            )
    ax.set_ylim([0, 70] if multicast else [0, 40])
    ax.legend(shadow=True, loc='upper left')
    ax.set_title('Average Failure Detection Time ')
    ax.set_xlabel('Failure Time (rounds of gossip)')
    ax.set_ylabel('Detection Time (rounds of gossip)')
    ax.tick_params(axis='both', which='major')
    ax.grid(True)
    ax.xaxis.set_ticks(list(set(aggregation['failure_delta'] / aggregation['gossip_delta'])))
    figure.savefig(path, bbox_inches='tight')
    plt.close(figure)


@click.command()
@click.option('--reports-path', help='Base path where to find the reports.', prompt=True)
@click.option('--output-path', help='Directory where to store the result of the analysis.', prompt=True)
@click.option('--use-cache', help='Use the cached results if available.', prompt=False, default=False, type=bool)
def main(reports_path, output_path, use_cache):
    """
    Analyze the results of the experiments and produces
    useful plots to include in the final report.
    """

    # create directory for the results
    if not os.path.exists(output_path):
        os.makedirs(output_path)

    # analyze the results
    cache = output_path + os.sep + 'results.csv'
    if use_cache and os.path.exists(cache):
        frame = pandas.DataFrame.from_csv(cache, index_col=None)
    else:
        frame = analyze_results(reports_path)
        frame.to_csv(cache, index=False)

    # plots
    plot(frame, output_path + os.sep + 'n50__average__no_catastrophe__no_multicast.png', '2407_n50', 0, False, False)
    plot(frame, output_path + os.sep + 'n50__average__catastrophe__no_multicast.png', '2407_n50', 0, True, False)
    plot(frame, output_path + os.sep + 'n50__average__catastrophe__multicast.png', '2407_n50', 0, True, True)


# entry point for the script
if __name__ == '__main__':
    main()
