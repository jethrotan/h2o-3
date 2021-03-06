package hex;

import hex.genmodel.GenModel;
import water.exceptions.H2OIllegalArgumentException;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.ArrayUtils;
import water.util.ModelUtils;
import water.util.TwoDimTable;

import java.util.Arrays;

public class ModelMetricsMultinomial extends ModelMetricsSupervised {
  public final float[] _hit_ratios;         // Hit ratios
  public final ConfusionMatrix _cm;
  public final double _logloss;

  public ModelMetricsMultinomial(Model model, Frame frame, double mse, String[] domain, double sigma, ConfusionMatrix cm, float[] hr, double logloss) {
    super(model, frame, mse, domain, sigma);
    _cm = cm;
    _hit_ratios = hr;
    _logloss = logloss;
  }

  @Override public ConfusionMatrix cm() { return _cm; }
  @Override public float[] hr() { return _hit_ratios; }

  public static ModelMetricsMultinomial getFromDKV(Model model, Frame frame) {
    ModelMetrics mm = ModelMetrics.getFromDKV(model, frame);

    if (! (mm instanceof ModelMetricsMultinomial))
      throw new H2OIllegalArgumentException("Expected to find a Multinomial ModelMetrics for model: " + model._key.toString() + " and frame: " + frame._key.toString(),
              "Expected to find a ModelMetricsMultinomial for model: " + model._key.toString() + " and frame: " + frame._key.toString() + " but found a: " + mm.getClass());

    return (ModelMetricsMultinomial) mm;
  }

  public static void updateHits(double w, int iact, double[] ds, double[] hits) {
    if (iact == ds[0]) { hits[0]++; return; }
    double before = ArrayUtils.sum(hits);
    // Use getPrediction logic to see which top K labels we would have predicted
    // Pick largest prob, assign label, then set prob to 0, find next-best label, etc.
    double[] ds_copy = Arrays.copyOf(ds, ds.length); //don't modify original ds!
    ds_copy[1+(int)ds[0]] = 0;
    for (int k=1; k<hits.length; ++k) {
      final int pred_labels = GenModel.getPrediction(ds_copy, ds, 0.5 /*ignored*/); //use tie-breaking of getPrediction
      ds_copy[1+pred_labels] = 0; //next iteration, we'll find the next-best label
      if (pred_labels==iact) {
        hits[k]+=w;
        break;
      }
    }
    // must find at least one hit if K == n_classes
    if (hits.length == ds.length-1) {
      double after = ArrayUtils.sum(hits);
      if (after == before) hits[hits.length-1]+=w; //assume worst case
    }
  }

  public static TwoDimTable getHitRatioTable(float[] hits) {
    String tableHeader = "Top-" + hits.length + " Hit Ratios";
    String[] rowHeaders = new String[hits.length];
    for (int k=0; k<hits.length; ++k)
      rowHeaders[k] = Integer.toString(k+1);
    String[] colHeaders = new String[]{"Hit Ratio"};
    String[] colTypes = new String[]{"float"};
    String[] colFormats = new String[]{"%f"};
    String colHeaderForRowHeaders = "K";
    TwoDimTable table = new TwoDimTable(tableHeader, null/*tableDescription*/, rowHeaders, colHeaders, colTypes, colFormats, colHeaderForRowHeaders);
    for (int k=0; k<hits.length; ++k)
      table.set(k, 0, hits[k]);
    return table;
  }


  public static class MetricBuilderMultinomial<T extends MetricBuilderMultinomial<T>> extends MetricBuilderSupervised<T> {
    double[/*nclasses*/][/*nclasses*/] _cm;
    double[/*K*/] _hits;            // the number of hits for hitratio, length: K
    int _K;               // TODO: Let user set K
    double _logloss;

    public MetricBuilderMultinomial( int nclasses, String[] domain ) {
      super(nclasses,domain);
      _cm = new double[domain.length][domain.length];
      _K = Math.min(10,_nclasses);
      _hits = new double[_K];
    }

    // Passed a float[] sized nclasses+1; ds[0] must be a prediction.  ds[1...nclasses-1] must be a class
    // distribution;
    @Override public double[] perRow(double ds[], float[] yact, Model m) { return perRow(ds, yact, 1, 0, m); }
    @Override public double[] perRow(double ds[], float[] yact, double w, double o, Model m) {
      if( Float .isNaN(yact[0]) ) return ds; // No errors if   actual   is missing
      if(ArrayUtils.hasNaNs(ds)) return ds;
      if(w == 0 || Double.isNaN(w)) return ds;
      final int iact = (int)yact[0];
      _wsum += w;
      _count++;

      // Compute error
      double err = iact+1 < ds.length ? 1-ds[iact+1] : 1;  // Error: distance from predicting ycls as 1.0
      _sumsqe += w*err*err;        // Squared error
      assert !Double.isNaN(_sumsqe);

      // Plain Olde Confusion Matrix
      _cm[iact][(int)ds[0]]++; // actual v. predicted

      // Compute hit ratio
      if( _K > 0 && iact < ds.length-1) updateHits(w,iact,ds,_hits);

      // Compute log loss
      final double eps = 1e-15;
      _logloss -= w*Math.log(Math.max(eps, 1-err));

      return ds;                // Flow coding
    }

    @Override public void reduce( T mb ) {
      super.reduce(mb);
      assert mb._K == _K;
      ArrayUtils.add(_cm, mb._cm);
      _hits = ArrayUtils.add(_hits, mb._hits);
      _logloss += mb._logloss;
    }

    @Override public ModelMetrics makeModelMetrics( Model m, Frame f, double sigma) {
      if (sigma != 0) {
        ConfusionMatrix cm = new ConfusionMatrix(_cm, _domain);
        float[] hr = new float[_K];
        double mse = Double.NaN;
        double logloss = Double.NaN;
        if (_wsum > 0) {
          if (_hits != null) {
            for (int i = 0; i < hr.length; i++)  hr[i] = (float)(_hits[i] / _wsum);
            for (int i = 1; i < hr.length; i++)  hr[i] += hr[i-1];
          }
          mse = _sumsqe / _wsum;
          logloss = _logloss / _wsum;
        }
        return m._output.addModelMetrics(new ModelMetricsMultinomial(m, f, mse, _domain, sigma, cm, hr, logloss));
      } else {
        return m._output.addModelMetrics(new ModelMetricsMultinomial(m, f, Double.NaN, null, Double.NaN, null, null, Double.NaN));
      }
    }
  }
}
