package com.hanpingchinese.strokedatabuilder;

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.Button;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
	private static final String TAG = MainActivity.class.getSimpleName();

	private static final int BUFFER_SIZE = 16 * 1024;

	private static final String GRAPHICS_FILENAME = "graphics.txt";
	private static final String BASE_CHARS_FILENAME = "base_chars.txt";

	public static final String DATA_FILENAME = "strokes.dat";

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		Button buildButton = (Button) findViewById(R.id.buildButton);
		buildButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				try {
					LinkedHashSet<Integer> baseChars = readChars(BASE_CHARS_FILENAME, getAssets());
					buildPluginZip(baseChars, null);
				}
				catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
		});
	}

	protected static LinkedHashSet<Integer> readChars(String assetPath, AssetManager assetMgr) {
		BufferedReader br = null;
		try {
			InputStream in = assetMgr.open(assetPath);
			br = new BufferedReader(new InputStreamReader(in), BUFFER_SIZE);
			LinkedHashSet<Integer> result = new LinkedHashSet<>();
			String nextLine;
			while ((nextLine = br.readLine()) != null) {
				if (nextLine.trim().length() == 0) {
					// ignoring blank
					continue;
				}
				if (nextLine.startsWith("//")) {
					Log.i(TAG, "Comment: " + nextLine);
					continue;
				}
				Iterator<Integer> it = codePoints(nextLine.trim());
				while (it.hasNext()) {
					result.add(it.next());
				}
			}
			Log.i(TAG, "Number of chars in " + assetPath + ": " + result.size());
			return result;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		finally {
			if (br != null) {
				try {
					br.close();
				}
				catch (IOException e) {
				}
			}
		}
	}

	protected static Iterator<Integer> codePoints(final CharSequence cs) {
		if (cs == null) {
			return null;
		}
		return new Iterator<Integer>() {
			int nextOffset = 0;

			public boolean hasNext() {
				return nextOffset < cs.length();
			}

			public Integer next() {
				int result = Character.codePointAt(cs, nextOffset);
				nextOffset += Character.charCount(result);
				return result;
			}

			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	protected void buildPluginZip(final LinkedHashSet<Integer> baseHanziCps, final LinkedHashSet<Integer> onlyAllowTheseHanziCps) {
		// run in a handler so that the UI can update (needs the onActivityResult to return)
		new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
			@Override
			public void run() {
				InputStream in = null;
				try {
					in = getAssets().open(GRAPHICS_FILENAME);
					Map<Integer, byte[]> strokeDataMap = readStrokeData(in);
					LinkedHashMap<Integer, byte[]> fullStrokeDataMap = createFullMap(baseHanziCps, strokeDataMap);
					File filesDir = getExternalFilesDir(null);
					File strokesDatFile = new File(filesDir, DATA_FILENAME);
					strokesDatFile.delete();

					FileOutputStream fout = new FileOutputStream(strokesDatFile);
					writeStrokeData(fullStrokeDataMap, onlyAllowTheseHanziCps, fout);
					fout.close();
				}
				catch (Exception e) {
					throw new IllegalStateException(e);
				}
				finally {
					if (in != null) {
						try {
							in.close();
						}
						catch (IOException e) {
						}
					}
				}
			}
		}, 100L);
	}

	protected static LinkedHashMap<Integer, byte[]> createFullMap(LinkedHashSet<Integer> baseHanziCps, Map<Integer, byte[]> strokeDataMap) {
		LinkedHashSet<Integer> orderedHanziCps = new LinkedHashSet<>(baseHanziCps);
		orderedHanziCps.addAll(strokeDataMap.keySet());
		LinkedHashMap<Integer, byte[]> result = new LinkedHashMap<>();
		for (int hanziCp : orderedHanziCps) {
			byte[] blob = strokeDataMap.get(hanziCp);
			if (blob == null) {
				throw new IllegalStateException("hanzi (in " + BASE_CHARS_FILENAME + ") not found (in " + GRAPHICS_FILENAME + ")");
			}
			result.put(hanziCp, blob);
		}
		return result;
	}

	/**
	 * @return Pair first: hanzi count (with stroke data), second: number of bytes written to output stream
	 */
	protected static Pair<Integer, Integer> writeStrokeData(LinkedHashMap<Integer, byte[]> strokeDataMap, LinkedHashSet<Integer> onlyAllowTheseHanziCps, OutputStream strokesDatOut) throws Exception {
		int numHanziWritten = 0;

		try {
			ByteArrayOutputStream dataOut = new ByteArrayOutputStream(6 * 1024 * 1024); // less than 6MB data

			LinkedHashMap<Integer, Integer> indexMap = new LinkedHashMap<>();

			for (Map.Entry<Integer, byte[]> me : strokeDataMap.entrySet()) {
				int hanziCp = me.getKey();
				boolean dataIncluded = onlyAllowTheseHanziCps == null || onlyAllowTheseHanziCps.contains(hanziCp);

				byte[] blob = dataIncluded ? me.getValue() : null;

				indexMap.put(hanziCp, blob == null ? 0 : blob.length);

				if (blob != null) {
					dataOut.write(blob);
					numHanziWritten++;
				}
			}

			ByteArrayOutputStream indexOut = new ByteArrayOutputStream(4 * indexMap.size()); // 4 bytes per hanzi
			byte[] twoBytesBuf = new byte[2];

			// now we know the size of the index, we can increase all the offsets and write the index
			for (Map.Entry<Integer, Integer> me : indexMap.entrySet()) {
				int hanziCp = me.getKey();
				fillBuffer(hanziCp, twoBytesBuf);
				indexOut.write(twoBytesBuf);
				int length = me.getValue();
				fillBuffer(length, twoBytesBuf);
				indexOut.write(twoBytesBuf);
			}

			fillBuffer(indexMap.size(), twoBytesBuf);
			strokesDatOut.write(twoBytesBuf);
			strokesDatOut.write(indexOut.toByteArray());
			strokesDatOut.write(dataOut.toByteArray());

			int bytesWritten = 2 + indexOut.size() + dataOut.size();
			return Pair.create(numHanziWritten, bytesWritten);
		}
		catch (Exception e) {
			throw e;
		}
	}

	protected static void fillBuffer(int value, byte[] buf) {
		buf[0] = (byte)value;
		buf[1] = (byte)(value >> 8);
	}

	protected static Map<Integer, byte[]> readStrokeData(InputStream in) throws IOException {
		minByteCountForStroke = Integer.MAX_VALUE; // 11-172
		maxByteCountForStroke = Integer.MIN_VALUE;
		minDistanceToMedianEndPoint = Integer.MAX_VALUE; // // min: 31 (丶), max: 1471 (饢)
		maxDistanceToMedianEndPoint = Integer.MIN_VALUE;
		minCp = Integer.MAX_VALUE; // minCp: 11904, maxCp: 40890
		maxCp = Integer.MIN_VALUE;
		minLeft = Integer.MAX_VALUE; // minLeft: 17, minTop: 7, maxRight: 1020, maxBottom: 1016
		minTop = Integer.MAX_VALUE;
		maxRight = Integer.MIN_VALUE;
		maxBottom = Integer.MIN_VALUE;

		int minByteCountForAllStrokes = Integer.MAX_VALUE; // min: 31 (丶), max: 1471 (饢)
		int maxByteCountForAllStrokes = Integer.MIN_VALUE;

		int totalByteCount = 0; // 5220763

		Map<Integer, byte[]> result = new LinkedHashMap<>();

		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(in), BUFFER_SIZE);
			String nextLine;
			// map from translationId to lexicalItemId
			while ((nextLine = br.readLine()) != null) {
				String strokeData = nextLine.trim();
				if (strokeData.length() == 0) {
					// ignoring blank
					continue;
				}
				strokeData = strokeData.replaceAll("(\\d)\\.\\d+", "$1");
				Matcher m = HANZI_CHAR_PATTERN.matcher(strokeData);
				int hanziCp;
				if (!m.find()) {
					Log.w(TAG, "Unable to get hanziCp from line: " + strokeData);
					continue;
				}
				hanziCp = m.group(1).codePointAt(0);
				currentHanzi = new String(Character.toChars(hanziCp));
				if (hanziCp < minCp) {
					minCp = hanziCp;
				} else if (hanziCp > maxCp) {
					maxCp = hanziCp;
				}

				if (strokeData.indexOf('.') >= 0) {
					Log.d(TAG + currentHanzi, "contains period: " + strokeData);
				}
				byte[] blob = createBlob(strokeData);

				if (blob.length < minByteCountForAllStrokes) {
					minByteCountForAllStrokes = blob.length;
					hanziCpForMinByteCount = currentHanzi.codePointAt(0);
				}
				if (blob.length > maxByteCountForAllStrokes) {
					maxByteCountForAllStrokes = blob.length;
					hanziCpForMaxByteCount = currentHanzi.codePointAt(0);
				}
				totalByteCount += blob.length;

				result.put(hanziCp, blob);
			}

			Log.d(TAG, "bytesCount for stroke: " + minByteCountForStroke + "-" + maxByteCountForStroke);
			Log.d(TAG, String.format("bytesCount for all strokes: min: %s (%s), max: %s (%s)", minByteCountForAllStrokes, new String(Character.toChars(hanziCpForMinByteCount)), maxByteCountForAllStrokes, new String(Character.toChars(hanziCpForMaxByteCount))));
			Log.d(TAG, String.format("distance to median end point: min: %s (%s), max: %s (%s)", minDistanceToMedianEndPoint, new String(Character.toChars(hanziCpForMinDistance)), maxDistanceToMedianEndPoint, new String(Character.toChars(hanziCpForMaxDistance))));
			Log.d(TAG, String.format("minCp: %s, maxCp: %s", minCp, maxCp));
			Log.d(TAG, String.format("minLeft: %s, minTop: %s, maxRight: %s, maxBottom: %s", minLeft, minTop, maxRight, maxBottom));
			Log.d(TAG, "total bytes for all strokes: " + totalByteCount);
		} finally {
			if (br != null) {
				br.close();
			}
		}
		return result;
	}

	private static final Pattern STROKE_PATTERN = Pattern.compile("\"(.*?)\"");
	private static final Pattern STROKE_CMP_PATTERN = Pattern.compile("([MQL]) ([-]?\\d+) ([-]?\\d+)(?: ([-]?\\d+) ([-]?\\d+))?");

	private static final Pattern MEDIAN_PATTERN = Pattern.compile("\\[(\\[.*?\\])\\]");
	private static final Pattern MEDIAN_CMP_PATTERN = Pattern.compile("\\[([-]?\\d+),([-]?\\d+)\\]");

	private static final Pattern HANZI_CHAR_PATTERN = Pattern.compile("[\"]character[\"]:[\"](.)[\"]");

	private static int minByteCountForStroke, maxByteCountForStroke;
	private static int hanziCpForMinByteCount, hanziCpForMaxByteCount;
	private static int minDistanceToMedianEndPoint, maxDistanceToMedianEndPoint;
	private static int hanziCpForMinDistance, hanziCpForMaxDistance;
	private static int minCp, maxCp;
	private static int minLeft, minTop, maxRight, maxBottom;

	private static String currentHanzi;

	/**
	 * ...
	 * repeat for each stroke:
	 * 0: distance along curve to end point
	 * 1: startX
	 * 2: startY
	 *
	 * repeat for each line/quad
	 *
	 * n: 0 means end of stroke (not necessary for final stroke), 1 means straight line, anything else is quad (control point x)
	 * for quad: n+1: control point y
	 *           n+2: target point x
	 *           n+3: target point y
	 * for line: n+1: target point x
	 *           n+2: target point y
	 */
	protected static byte[] createBlob(String strokeData) throws IOException {
		String strokesStarter = ",\"strokes\":[";
		String mediansStarter = "],\"medians\":[";
		int mediansIndex = strokeData.indexOf(mediansStarter);

		String strokeText = strokeData.substring(strokeData.indexOf(strokesStarter) + strokesStarter.length(), mediansIndex);
		//String[] strokeTexts = strokeText.split("[\"][,][\"]");

		String medianText = strokeData.substring(mediansIndex + mediansStarter.length(), strokeData.indexOf("]}", mediansIndex));
		//String[] medianTexts = medianText.split("\\]\\][,]\\[\\[");

		//Log.d(TAG, Arrays.toString(strokeTexts));
		//Log.d(TAG, Arrays.toString(medianTexts));

		List<Point> medianStartPoints = new ArrayList<>();
		List<Point> medianEndPoints = new ArrayList<>();

		Matcher medianMatcher = MEDIAN_PATTERN.matcher(medianText);
		while (medianMatcher.find()) {
			String thisMedianText = medianMatcher.group();
			Matcher medianCmpMatcher = MEDIAN_CMP_PATTERN.matcher(thisMedianText);
			Point medianStartPoint = null;
			Point thisPoint = null;
			while (medianCmpMatcher.find()) {
				int arg0 = Integer.valueOf(medianCmpMatcher.group(1));
				int arg1 = (900 - Integer.valueOf(medianCmpMatcher.group(2)));
				thisPoint = new Point(arg0, arg1);
				if (medianStartPoint == null) {
					medianStartPoint = thisPoint;
				}
			}
			medianStartPoints.add(medianStartPoint);
			medianEndPoints.add(thisPoint);
		}

		ByteArrayOutputStream resultBytesOut = new ByteArrayOutputStream(1500); // all stroke bytes from 31 bytes (丶) to 1471 bytes (饢)

		int index = 0;
		Matcher m = STROKE_PATTERN.matcher(strokeText);
		while (m.find()) {
			String thisStrokeText = m.group();
			Matcher m2 =  STROKE_CMP_PATTERN.matcher(thisStrokeText);
			Point medianStartPoint = medianStartPoints.get(index);
			Point medianEndPoint = medianEndPoints.get(index);
			index++;
			int startX = -1, startY = -1;
			double minDistanceToStart = Double.MAX_VALUE;
			int contourIndex = -1;
			int contourStartIndex = -1;
			List<Pair<Point, Point>> contours = new ArrayList<>();
			while (m2.find()) {
				contourIndex++;
				char code = m2.group(1).charAt(0);
				int arg0 = Integer.valueOf(m2.group(2));
				int arg1 = (900 - Integer.valueOf(m2.group(3)));
				Pair<Point, Point> thisPair;
				switch (code) {
					case 'M':
						startX = arg0;
						startY = arg1;
						thisPair = Pair.create(new Point(arg0, arg1), (Point)null);
						break;
					case 'L':
						if (startX == arg0 && startY == arg1 && thisStrokeText.charAt(m2.end() + 1) == 'Z') {
							//Log.d(TAG, "ignoring line to: " + arg0 + "," + arg1 + " because will close path anyway");
							continue;
						}
						thisPair = Pair.create(new Point(arg0, arg1), (Point)null);
						break;
					case 'Q':
						int arg2 = Integer.valueOf(m2.group(4));
						int arg3 = (900 - Integer.valueOf(m2.group(5)));
						thisPair = Pair.create(new Point(arg2, arg3), new Point(arg0, arg1));
						break;
					default:
						throw new IllegalStateException("Unknown curve code: " + code);
				}
				contours.add(thisPair);
				double distanceToStart = Math.hypot(thisPair.first.x - medianStartPoint.x, thisPair.first.y - medianStartPoint.y);
				if (distanceToStart < minDistanceToStart) {
					minDistanceToStart = distanceToStart;
					contourStartIndex = contourIndex;
				}
				Point pos = thisPair.first;
				if (pos.x < minLeft) {
					minLeft = pos.x;
				} else if (pos.x > maxRight) {
					maxRight = pos.x;
				}
				if (pos.y < minTop) {
					minTop = pos.y;
				} else if (pos.y > maxBottom) {
					maxBottom = pos.y;
				}
			}
			if (contours.size() > 1 && contours.get(0).first.equals(contours.get(contours.size() - 1).first)) {
				// the first one corresponds to a moveTo, so we can remove that one
				// the last one might be a quad (so we shouldn't remove that)
				// this happens when the last contour is a quad finishing at the start point
				//Log.d(TAG, "removing first contour because same point as target of last contour");
				contours.remove(0);
				if (contourStartIndex == 0) {
					contourStartIndex = contours.size() - 1;
				}
				else {
					contourStartIndex--;
				}
			}
			ByteArrayOutputStream strokeBytesOut = new ByteArrayOutputStream(200); // strokes need between 11-172 bytes including end stroke op
			Path outlinePath = new Path();
			Point startPoint = contours.get(contourStartIndex).first;
			// first we need to move to the point
			outlinePath.moveTo(startPoint.x, startPoint.y);
			addLineToBytes(startPoint, true, strokeBytesOut);
			int distanceToMedianEndPoint = -1;
			double minDirectDistanceToMedianEndPoint = Double.MAX_VALUE;
			Point endPoint = null;
			for (int i = 0; i < contours.size(); i++) {
				// but the first line is actually the contour after the start contour (so +1)
				int actualIndex = (contourStartIndex + 1 + i) % contours.size();
				Pair<Point, Point> contour = contours.get(actualIndex);
				if (contour.second == null) {
					if (i == contours.size() - 1) {
						// this is the last contour, so let's make sure it's not a redundant line
						if (contour.first.equals(startPoint)) {
							break;
						}
					}
					addLineToBytes(contour.first, false, strokeBytesOut);
					outlinePath.lineTo(contour.first.x, contour.first.y);
				}
				else {
					addQuadToBytes(contour.first, contour.second, strokeBytesOut);
					outlinePath.quadTo(contour.second.x, contour.second.y, contour.first.x, contour.first.y);
				}
				double directDistanceToMedianEndPoint = Math.hypot(contour.first.x - medianEndPoint.x, contour.first.y - medianEndPoint.y);
				if (directDistanceToMedianEndPoint < minDirectDistanceToMedianEndPoint) {
					minDirectDistanceToMedianEndPoint = directDistanceToMedianEndPoint;
					PathMeasure pm = new PathMeasure(outlinePath, false);
					distanceToMedianEndPoint = (int)pm.getLength();
					endPoint = contour.first;
				}
			}
			if (endPoint == null) {
				throw new NullPointerException("endPoint null");
			}

			if (strokeBytesOut.size() < minByteCountForStroke) {
				minByteCountForStroke = strokeBytesOut.size();
			}
			if (strokeBytesOut.size() > maxByteCountForStroke) {
				maxByteCountForStroke = strokeBytesOut.size();
			}
			if (distanceToMedianEndPoint < minDistanceToMedianEndPoint) {
				minDistanceToMedianEndPoint = distanceToMedianEndPoint;
				hanziCpForMinDistance = currentHanzi.codePointAt(0);
			}
			if (distanceToMedianEndPoint > maxDistanceToMedianEndPoint) {
				maxDistanceToMedianEndPoint = distanceToMedianEndPoint;
				hanziCpForMaxDistance = currentHanzi.codePointAt(0);
			}
			resultBytesOut.write(transformArg(distanceToMedianEndPoint / 2, false)); // longest distance is 1904 乙 (so divide by 2 to keep it in range)
			resultBytesOut.write(strokeBytesOut.toByteArray());
			resultBytesOut.write(END_STROKE_OP);
		}
		byte[] result = Arrays.copyOf(resultBytesOut.toByteArray(), resultBytesOut.size() - 1); // we don't need the last byte to end the last stroke
		return result;
	}

	private static void addLineToBytes(Point targetPoint, boolean isFirstPoint, ByteArrayOutputStream out) throws IOException {
		if (!isFirstPoint) {
			out.write(LINE_TO_OP);
		}
		out.write(transformArg(targetPoint.x, false));
		out.write(transformArg(targetPoint.y, false));
	}

	private static void addQuadToBytes(Point targetPoint, Point controlPoint, ByteArrayOutputStream out) throws IOException {
		out.write(transformArg(controlPoint.x, true));
		out.write(transformArg(controlPoint.y, false));
		out.write(transformArg(targetPoint.x, false));
		out.write(transformArg(targetPoint.y, false));
	}

	private static final int END_STROKE_OP = 0;
	private static final int LINE_TO_OP = 1;

	private static int transformArg(int arg, boolean avoidOpClashes) {
		int intResult = arg / 4; // in theory we should (arg+2)/4 but this makes no noticeable difference (anyway introduces a 256 value)
		int lowerBound = avoidOpClashes ? 2 : 0;
		if (intResult < lowerBound) {
			Log.d(TAG + currentHanzi, "adjusting coord value: " + intResult);
			intResult = lowerBound;
		}
		else if (intResult > 255) {
			Log.d(TAG + currentHanzi, "adjusting coord value: " + intResult);
			intResult = 255;
		}
		return intResult;
	}
}