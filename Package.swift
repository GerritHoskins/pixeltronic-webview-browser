// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PixeltronicWebviewBrowser",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "PixeltronicWebviewBrowser", targets: ["PixeltronicWebviewBrowser"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.5.3")
    ],
    targets: [
        .target(
            name: "PixeltronicWebviewBrowser",
            dependencies: [.product(name: "Capacitor", package: "capacitor-swift-pm")],
            path: "ios/Plugin",
            exclude: ["InAppBrowserPlugin.h", "InAppBrowserPlugin.m", "Info.plist"]
        ),
        .testTarget(
            name: "PixeltronicWebviewBrowserTests",
            dependencies: [
                "PixeltronicWebviewBrowser",
                .product(name: "Capacitor", package: "capacitor-swift-pm")
            ],
            path: "ios/PluginTests",
            exclude: ["Info.plist"]
        )
    ]
)
