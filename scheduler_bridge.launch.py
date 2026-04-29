from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.conditions import IfCondition
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue


def generate_launch_description():
    use_http_bridge = LaunchConfiguration('use_http_bridge')
    publish_tf_pose_to_amcl = LaunchConfiguration('publish_tf_pose_to_amcl')
    http_address = LaunchConfiguration('http_address')
    http_port = LaunchConfiguration('http_port')
    global_frame = LaunchConfiguration('global_frame')
    robot_frame = LaunchConfiguration('robot_frame')
    pose_topic = LaunchConfiguration('pose_topic')
    pose_publish_rate = LaunchConfiguration('pose_publish_rate')

    return LaunchDescription([
        DeclareLaunchArgument(
            'use_http_bridge',
            default_value='true',
            description='Start HTTP bridge for Robot Scheduler backend.'),
        DeclareLaunchArgument(
            'publish_tf_pose_to_amcl',
            default_value='true',
            description='Publish TF-derived robot pose as /amcl_pose for scheduler compatibility.'),
        DeclareLaunchArgument(
            'http_address',
            default_value='0.0.0.0',
            description='Address HTTP bridge listens on.'),
        DeclareLaunchArgument(
            'http_port',
            default_value='8080',
            description='Port HTTP bridge listens on.'),
        DeclareLaunchArgument(
            'global_frame',
            default_value='map',
            description='Global frame used by the scheduler pose topic.'),
        DeclareLaunchArgument(
            'robot_frame',
            default_value='base_link',
            description='Robot base frame used to calculate scheduler pose.'),
        DeclareLaunchArgument(
            'pose_topic',
            default_value='/amcl_pose',
            description='Pose topic consumed by the scheduler backend.'),
        DeclareLaunchArgument(
            'pose_publish_rate',
            default_value='10.0',
            description='TF-derived pose publish rate in Hz.'),

        Node(
            condition=IfCondition(use_http_bridge),
            package='cod_bringup',
            executable='http_scheduler_bridge.py',
            name='http_scheduler_bridge',
            output='screen',
            parameters=[{
                'http_address': http_address,
                'http_port': ParameterValue(http_port, value_type=int),
                'pose_topic': pose_topic,
            }],
        ),
        Node(
            condition=IfCondition(publish_tf_pose_to_amcl),
            package='cod_bringup',
            executable='tf_pose_to_amcl',
            name='tf_pose_to_amcl',
            output='screen',
            parameters=[{
                'global_frame': global_frame,
                'robot_frame': robot_frame,
                'pose_topic': pose_topic,
                'publish_rate': ParameterValue(pose_publish_rate, value_type=float),
            }],
        ),
    ])
