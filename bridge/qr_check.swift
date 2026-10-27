import CoreImage
import Foundation
let rows = try! String(contentsOfFile: CommandLine.arguments[1]).split(separator: "\n").map { Array($0) }
let n = rows.count, s = 8, q = 4, W = (n + 2*q) * s
var px = [UInt8](repeating: 255, count: W*W)
for r in 0..<n { for c in 0..<n where rows[r][c] == "1" { for y in 0..<s { for x in 0..<s { px[((r+q)*s+y)*W + (c+q)*s+x] = 0 } } } }
let cs = CGColorSpaceCreateDeviceGray()
let ctx = CGContext(data: &px, width: W, height: W, bitsPerComponent: 8, bytesPerRow: W, space: cs, bitmapInfo: 0)!
let img = CIImage(cgImage: ctx.makeImage()!)
let d = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])!
let f = d.features(in: img)
print((f.first as? CIQRCodeFeature)?.messageString ?? "NONE")
